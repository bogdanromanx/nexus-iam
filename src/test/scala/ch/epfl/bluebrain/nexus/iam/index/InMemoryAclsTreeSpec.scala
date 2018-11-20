package ch.epfl.bluebrain.nexus.iam.index

import java.time.{Clock, Instant, ZoneId}

import ch.epfl.bluebrain.nexus.iam.acls.{AccessControlList, AccessControlLists, base, _}
import ch.epfl.bluebrain.nexus.iam.config.AppConfig.HttpConfig
import ch.epfl.bluebrain.nexus.iam.types.Identity._
import ch.epfl.bluebrain.nexus.iam.types._
import ch.epfl.bluebrain.nexus.rdf.Iri.Path
import ch.epfl.bluebrain.nexus.rdf.Iri.Path._
import org.scalatest._

class InMemoryAclsTreeSpec extends WordSpecLike with Matchers with OptionValues with Inspectors with EitherValues {
  private val clock: Clock  = Clock.fixed(Instant.ofEpochSecond(3600), ZoneId.systemDefault())
  private implicit val http = HttpConfig("some", 8080, "v1", "http://nexus.example.com")
  private val instant       = clock.instant()

  "A in memory Acls index" should {
    val index = InMemoryAclsTree()

    val user  = User("uuid", "realm")
    val user2 = User("uuid2", "realm")
    val group = Group("group", "realm")

    val read: Permission  = Permission.unsafe("read")
    val write: Permission = Permission.unsafe("write")
    val other: Permission = Permission.unsafe("other")

    val aclProject =
      ResourceF(base + "id1",
                1L,
                Set.empty,
                instant,
                user,
                instant,
                user2,
                AccessControlList(user -> Set(read, write), group -> Set(other)))
    val aclProject1_org1 =
      ResourceF(base + "id2",
                2L,
                Set.empty,
                instant,
                user,
                instant,
                user2,
                AccessControlList(user -> Set(read), group -> Set(read)))
    val aclOrg =
      ResourceF(base + "id3",
                3L,
                Set.empty,
                instant,
                user,
                instant,
                user2,
                AccessControlList(user2 -> Set(read, other), group -> Set(write, writeAcls)))
    val aclOrg2 =
      ResourceF(base + "id4",
                4L,
                Set.empty,
                instant,
                user,
                instant,
                user2,
                AccessControlList(user -> Set(other, writeAcls)))
    val aclProject1_org2 =
      ResourceF(base + "id5", 5L, Set.empty, instant, user, instant, user2, AccessControlList(group -> Set(write)))
    val aclProject2_org1 =
      ResourceF(base + "id6",
                6L,
                Set.empty,
                instant,
                user,
                instant,
                user2,
                AccessControlList(group -> Set(other), user -> Set(write)))
    val aclRoot =
      ResourceF(base + "id7",
                7L,
                Set.empty,
                instant,
                user,
                instant,
                user2,
                AccessControlList(user2 -> Set(other, writeAcls), group -> Set(read)))

    val options = List(true -> true, false -> false, true -> false, false -> true)

    "create ACLs on /org1/proj1" in {
      index.replace("org1" / "proj1", aclProject) shouldEqual true
    }

    "fetch ACLs on /org1/proj1" in {
      forAll(options) {
        case (ancestors, self) =>
          index.get("org1" / "proj1", ancestors, self)(Set(user, group)) shouldEqual
            AccessControlLists("org1" / "proj1" -> aclProject)

          index.get("org1" / "proj1", ancestors, self)(Set(user2)) shouldEqual AccessControlLists.empty
      }
      index.get("org1" / "proj1", ancestors = false, self = true)(Set(user)) shouldEqual
        AccessControlLists("org1" / "proj1" -> aclProject.map(_ => AccessControlList(user -> Set(read, write))))

      index.get("org1" / "proj1", ancestors = true, self = true)(Set(group)) shouldEqual
        AccessControlLists("org1" / "proj1" -> aclProject.map(_ => AccessControlList(group -> Set(other))))
    }

    "add ACLs on /org1" in {
      index.replace(Path("/org1").right.value, aclOrg) shouldEqual true
    }

    "failed to add ACLs on /org1 with same revision" in {
      val acl =
        ResourceF(base + "id2",
                  2L,
                  Set.empty,
                  instant,
                  user,
                  instant,
                  user2,
                  AccessControlList(user2 -> Set(Permission("new").value)))
      index.replace(Path("/org1").right.value, acl) shouldEqual false
    }

    "fetch ACLs on /org1" in {
      forAll(options) {
        case (ancestors, self) =>
          index.get(Path("/org1").right.value, ancestors, self)(Set(user2, group)) shouldEqual
            AccessControlLists(Path("/org1").right.value -> aclOrg)

          index.get(Path("/org1").right.value, ancestors, self)(Set(user)) shouldEqual AccessControlLists.empty
      }
      index.get(Path("/org1").right.value, ancestors = false, self = true)(Set(user2)) shouldEqual
        AccessControlLists(Path("/org1").right.value -> aclOrg.map(_ => AccessControlList(user2 -> Set(read, other))))

      index.get(Path("/org1").right.value, ancestors = true, self = true)(Set(group)) shouldEqual
        AccessControlLists(
          Path("/org1").right.value -> aclOrg.map(_ => AccessControlList(group -> Set(write, writeAcls))))
    }

    "replace ACLs on /org1/proj1" in {
      index.replace("org1" / "proj1", aclProject1_org1) shouldEqual true
    }

    "fetch ACLs on /org1/proj1 after replace" in {
      index.get("org1" / "proj1", ancestors = false, self = true)(Set(user, group)) shouldEqual
        AccessControlLists("org1" / "proj1" -> aclProject1_org1)

      index.get("org1" / "proj1", ancestors = false, self = false)(Set(user, group)) shouldEqual
        AccessControlLists("org1" / "proj1" -> aclProject1_org1)

      index.get("org1" / "proj1", ancestors = false, self = true)(Set(user2)) shouldEqual AccessControlLists.empty
      index.get("org1" / "proj1", ancestors = false, self = false)(Set(user2)) shouldEqual AccessControlLists.empty

      index.get("org1" / "proj1", ancestors = false, self = true)(Set(user)) shouldEqual
        AccessControlLists("org1" / "proj1" -> aclProject1_org1.map(_ => AccessControlList(user -> Set(read))))

      index.get("org1" / "proj1", ancestors = true, self = true)(Set(group)) shouldEqual
        AccessControlLists(
          Path("/org1").right.value -> aclOrg.map(_ => AccessControlList(group           -> Set(write, writeAcls))),
          "org1" / "proj1"          -> aclProject1_org1.map(_ => AccessControlList(group -> Set(read)))
        )
    }

    "add ACLs on /" in {
      index.replace(/, aclRoot) shouldEqual true
    }

    "fetch ACLs on /" in {
      forAll(options) {
        case (ancestors, self) =>
          index.get(/, ancestors, self)(Set(user2, group)) shouldEqual AccessControlLists(/ -> aclRoot)

          index.get(/, ancestors, self)(Set(group)) shouldEqual
            AccessControlLists(/ -> aclRoot.map(_ => AccessControlList(group -> Set(read))))

          index.get(/, ancestors, self)(Set(user)) shouldEqual AccessControlLists.empty
      }
    }

    "add acls on /org2" in {
      index.replace(Path("/org2").right.value, aclOrg2) shouldEqual true
    }

    "fetch ACLs on /org2" in {
      index.get(Path("/org2").right.value, ancestors = true, self = true)(Set(user, group)) shouldEqual
        AccessControlLists(/                         -> aclRoot.map(_ => AccessControlList(group -> Set(read))),
                           Path("/org2").right.value -> aclOrg2)

      index.get(Path("/org2").right.value, ancestors = true, self = false)(Set(group)) shouldEqual
        AccessControlLists(/ -> aclRoot.map(_ => AccessControlList(group -> Set(read))))

      index.get(Path("/org2").right.value, ancestors = true, self = false)(Set(user2)) shouldEqual
        AccessControlLists(/ -> aclRoot, Path("/org2").right.value -> aclOrg2)

      forAll(options) {
        case (ancestors, self) =>
          index.get(Path("/org2").right.value, ancestors = false, self = false)(Set(user)) shouldEqual
            AccessControlLists(Path("/org2").right.value -> aclOrg2)

          index.get(Path("/org2").right.value, ancestors, self)(Set(Anonymous)) shouldEqual AccessControlLists.empty
      }
    }

    "fetch ACLs on /*" in {
      index.get(Path("/*").right.value, ancestors = true, self = true)(Set(user2)) shouldEqual
        AccessControlLists(/                         -> aclRoot.map(_ => AccessControlList(user2 -> Set(other, writeAcls))),
                           Path("/org1").right.value -> aclOrg.map(_ => AccessControlList(user2  -> Set(read, other))))

      index.get(Path("/*").right.value, ancestors = true, self = false)(Set(user2)) shouldEqual
        AccessControlLists(/ -> aclRoot, Path("/org1").right.value -> aclOrg, Path("/org2").right.value -> aclOrg2)

      index.get(Path("/*").right.value, ancestors = false, self = true)(Set(user2)) shouldEqual
        AccessControlLists(Path("/org1").right.value -> aclOrg.map(_ => AccessControlList(user2 -> Set(read, other))))

      index.get(Path("/*").right.value, ancestors = false, self = false)(Set(user2)) shouldEqual
        AccessControlLists(Path("/org1").right.value -> aclOrg, Path("/org2").right.value -> aclOrg2)

      index.get(Path("/*").right.value, ancestors = true, self = false)(Set(group)) shouldEqual
        AccessControlLists(/                         -> aclRoot.map(_ => AccessControlList(group -> Set(read))),
                           Path("/org1").right.value -> aclOrg)
    }

    "add acls on /org2/proj1" in {
      index.replace("org2" / "proj1", aclProject1_org2) shouldEqual true
    }

    "fetch ACLs on /org2/proj1" in {
      index.get("org2" / "proj1", ancestors = true, self = true)(Set(user, group)) shouldEqual
        AccessControlLists(/                         -> aclRoot.map(_ => AccessControlList(group -> Set(read))),
                           Path("/org2").right.value -> aclOrg2,
                           "org2" / "proj1"          -> aclProject1_org2)

      index.get("org2" / "proj1", ancestors = true, self = false)(Set(user, group)) shouldEqual
        AccessControlLists(/                         -> aclRoot.map(_ => AccessControlList(group -> Set(read))),
                           Path("/org2").right.value -> aclOrg2,
                           "org2" / "proj1"          -> aclProject1_org2)

      index.get("org2" / "proj1", ancestors = true, self = false)(Set(user)) shouldEqual
        AccessControlLists(Path("/org2").right.value -> aclOrg2, "org2" / "proj1" -> aclProject1_org2)

      index.get("org2" / "proj1", ancestors = true, self = false)(Set(group)) shouldEqual
        AccessControlLists(/                -> aclRoot.map(_ => AccessControlList(group          -> Set(read))),
                           "org2" / "proj1" -> aclProject1_org2.map(_ => AccessControlList(group -> Set(write))))

      index.get("org2" / "proj1", ancestors = false, self = false)(Set(group)) shouldEqual
        AccessControlLists("org2" / "proj1" -> aclProject1_org2.map(_ => AccessControlList(group -> Set(write))))

      index.get("org2" / "proj1", ancestors = false, self = true)(Set(group)) shouldEqual
        AccessControlLists("org2" / "proj1" -> aclProject1_org2.map(_ => AccessControlList(group -> Set(write))))

      index.get("org2" / "proj1", ancestors = false, self = false)(Set(user)) shouldEqual
        AccessControlLists("org2" / "proj1" -> aclProject1_org2.map(_ => AccessControlList(group -> Set(write))))

      index.get("org2" / "proj1", ancestors = false, self = true)(Set(user)) shouldEqual
        AccessControlLists.empty

      index.get("org2" / "proj1", ancestors = true, self = false)(Set(user2)) shouldEqual
        AccessControlLists(/ -> aclRoot, Path("/org2").right.value -> aclOrg2, "org2" / "proj1" -> aclProject1_org2)
    }

    "add acls on /org1/proj2" in {
      index.replace("org1" / "proj2", aclProject2_org1) shouldEqual true
    }

    "fetch ACLs on /org1/proj2" in {
      index.get("org1" / "proj2", ancestors = true, self = true)(Set(user, group)) shouldEqual
        AccessControlLists(
          /                         -> aclRoot.map(_ => AccessControlList(group -> Set(read))),
          Path("/org1").right.value -> aclOrg.map(_ => AccessControlList(group -> Set(write, writeAcls))),
          "org1" / "proj2"          -> aclProject2_org1
        )

      index.get("org1" / "proj2", ancestors = true, self = false)(Set(group)) shouldEqual
        AccessControlLists(/                         -> aclRoot.map(_ => AccessControlList(group -> Set(read))),
                           Path("/org1").right.value -> aclOrg,
                           "org1" / "proj2"          -> aclProject2_org1)

      index.get("org1" / "proj2", ancestors = true, self = false)(Set(user)) shouldEqual
        AccessControlLists("org1" / "proj2" -> aclProject2_org1.map(_ => AccessControlList(user -> Set(write))))

      index.get("org1" / "proj2", ancestors = false, self = false)(Set(group)) shouldEqual
        AccessControlLists("org1" / "proj2" -> aclProject2_org1)

      index.get("org1" / "proj2", ancestors = false, self = true)(Set(group)) shouldEqual
        AccessControlLists("org1" / "proj2" -> aclProject2_org1.map(_ => AccessControlList(group -> Set(other))))

      index.get("org1" / "proj2", ancestors = false, self = false)(Set(user)) shouldEqual
        AccessControlLists("org1" / "proj2" -> aclProject2_org1.map(_ => AccessControlList(user -> Set(write))))

      index.get("org1" / "proj2", ancestors = false, self = true)(Set(user)) shouldEqual
        AccessControlLists("org1" / "proj2" -> aclProject2_org1.map(_ => AccessControlList(user -> Set(write))))

    }

    "fetch ACLs on /*/proj1" in {
      index.get("*" / "proj1", ancestors = false, self = true)(Set(user)) shouldEqual
        AccessControlLists("org1" / "proj1" -> aclProject1_org1.map(_ => AccessControlList(user -> Set(read))))

      index.get("*" / "proj1", ancestors = false, self = true)(Set(group)) shouldEqual
        AccessControlLists(
          "org1" / "proj1" -> aclProject1_org1.map(_ => AccessControlList(group -> Set(read))),
          "org2" / "proj1" -> aclProject1_org2.map(_ => AccessControlList(group -> Set(write)))
        )

      index.get("*" / "proj1", ancestors = true, self = true)(Set(group)) shouldEqual
        AccessControlLists(
          "org1" / "proj1"          -> aclProject1_org1.map(_ => AccessControlList(group -> Set(read))),
          "org2" / "proj1"          -> aclProject1_org2,
          Path("/org1").right.value -> aclOrg.map(_ => AccessControlList(group -> Set(write, writeAcls))),
          /                         -> aclRoot.map(_ => AccessControlList(group -> Set(read)))
        )

      index.get("*" / "proj1", ancestors = true, self = false)(Set(group)) shouldEqual
        AccessControlLists(
          "org2" / "proj1"          -> aclProject1_org2,
          "org1" / "proj1"          -> aclProject1_org1,
          Path("/org1").right.value -> aclOrg,
          /                         -> aclRoot.map(_ => AccessControlList(group -> Set(read)))
        )
      index.get("*" / "proj1", ancestors = true, self = false)(Set(user2)) shouldEqual
        AccessControlLists(
          "org2" / "proj1"          -> aclProject1_org2,
          "org1" / "proj1"          -> aclProject1_org1,
          Path("/org1").right.value -> aclOrg,
          Path("/org2").right.value -> aclOrg2,
          /                         -> aclRoot
        )

      index.get("*" / "proj1", ancestors = false, self = true)(Set(user2)) shouldEqual
        AccessControlLists.empty

    }

    "fetch ACLs on /org1/*" in {
      forAll(options) {
        case (ancestors, self) =>
          index.get("org1" / "*", ancestors = ancestors, self = self)(Set(user)) shouldEqual
            AccessControlLists(
              "org1" / "proj1" -> aclProject1_org1.map(_ => AccessControlList(user -> Set(read))),
              "org1" / "proj2" -> aclProject2_org1.map(_ => AccessControlList(user -> Set(write)))
            )
      }

      index.get("org1" / "*", ancestors = false, self = false)(Set(group)) shouldEqual
        AccessControlLists("org1" / "proj1" -> aclProject1_org1, "org1" / "proj2" -> aclProject2_org1)

      index.get("org1" / "*", ancestors = true, self = false)(Set(group)) shouldEqual
        AccessControlLists(
          "org1" / "proj1"          -> aclProject1_org1,
          "org1" / "proj2"          -> aclProject2_org1,
          Path("/org1").right.value -> aclOrg,
          /                         -> aclRoot.map(_ => AccessControlList(group -> Set(read)))
        )

      index.get("org1" / "*", ancestors = true, self = false)(Set(user2)) shouldEqual
        AccessControlLists("org1" / "proj1"          -> aclProject1_org1,
                           "org1" / "proj2"          -> aclProject2_org1,
                           Path("/org1").right.value -> aclOrg,
                           /                         -> aclRoot)

      index.get("org1" / "*", ancestors = true, self = true)(Set(user2)) shouldEqual
        AccessControlLists(/                         -> aclRoot.map(_ => AccessControlList(user2 -> Set(other, writeAcls))),
                           Path("/org1").right.value -> aclOrg.map(_ => AccessControlList(user2  -> Set(read, other))))

    }

    "fetch ACLs on /*/*" in {
      index.get("*" / "*", ancestors = true, self = true)(Set(user)) shouldEqual
        AccessControlLists(
          "org1" / "proj1"          -> aclProject1_org1.map(_ => AccessControlList(user -> Set(read))),
          "org1" / "proj2"          -> aclProject2_org1.map(_ => AccessControlList(user -> Set(write))),
          Path("/org2").right.value -> aclOrg2.map(_ => AccessControlList(user          -> Set(other, writeAcls)))
        )

      index.get("*" / "*", ancestors = false, self = true)(Set(user)) shouldEqual
        AccessControlLists(
          "org1" / "proj1" -> aclProject1_org1.map(_ => AccessControlList(user -> Set(read))),
          "org1" / "proj2" -> aclProject2_org1.map(_ => AccessControlList(user -> Set(write)))
        )

      index.get("*" / "*", ancestors = true, self = false)(Set(user)) shouldEqual
        AccessControlLists(
          "org1" / "proj1"          -> aclProject1_org1.map(_ => AccessControlList(user -> Set(read))),
          "org1" / "proj2"          -> aclProject2_org1.map(_ => AccessControlList(user -> Set(write))),
          "org2" / "proj1"          -> aclProject1_org2,
          Path("/org2").right.value -> aclOrg2.map(_ => AccessControlList(user -> Set(other, writeAcls)))
        )

      index.get("*" / "*", ancestors = false, self = false)(Set(user)) shouldEqual
        AccessControlLists(
          "org1" / "proj1" -> aclProject1_org1.map(_ => AccessControlList(user -> Set(read))),
          "org1" / "proj2" -> aclProject2_org1.map(_ => AccessControlList(user -> Set(write))),
          "org2" / "proj1" -> aclProject1_org2
        )

      index.get("*" / "*", ancestors = true, self = false)(Set(group)) shouldEqual
        AccessControlLists(
          /                         -> aclRoot.map(_ => AccessControlList(group -> Set(read))),
          Path("/org1").right.value -> aclOrg,
          "org1" / "proj1"          -> aclProject1_org1,
          "org1" / "proj2"          -> aclProject2_org1,
          "org2" / "proj1"          -> aclProject1_org2
        )

      index.get("*" / "*", ancestors = true, self = false)(Set(user2)) shouldEqual
        AccessControlLists(
          /                         -> aclRoot,
          Path("/org1").right.value -> aclOrg,
          Path("/org2").right.value -> aclOrg2,
          "org1" / "proj1"          -> aclProject1_org1,
          "org1" / "proj2"          -> aclProject2_org1,
          "org2" / "proj1"          -> aclProject1_org2
        )

      index.get("*" / "*", ancestors = false, self = false)(Set(user2)) shouldEqual
        AccessControlLists(
          "org1" / "proj1" -> aclProject1_org1,
          "org1" / "proj2" -> aclProject2_org1,
          "org2" / "proj1" -> aclProject1_org2
        )
    }
  }
}