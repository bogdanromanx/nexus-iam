package ch.epfl.bluebrain.nexus.iam.acls

import java.time.Clock

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import cats.Monad
import cats.effect.{Async, ConcurrentEffect}
import cats.implicits._
import ch.epfl.bluebrain.nexus.iam.acls.AclCommand._
import ch.epfl.bluebrain.nexus.iam.acls.AclEvent._
import ch.epfl.bluebrain.nexus.iam.acls.AclRejection._
import ch.epfl.bluebrain.nexus.iam.acls.AclState.{Current, Initial}
import ch.epfl.bluebrain.nexus.iam.acls.Acls._
import ch.epfl.bluebrain.nexus.iam.config.AppConfig
import ch.epfl.bluebrain.nexus.iam.config.AppConfig.{HttpConfig, InitialAcl}
import ch.epfl.bluebrain.nexus.iam.index.AclsIndex
import ch.epfl.bluebrain.nexus.iam.syntax._
import ch.epfl.bluebrain.nexus.iam.types.Caller
import ch.epfl.bluebrain.nexus.rdf.Iri.Path
import ch.epfl.bluebrain.nexus.sourcing.Aggregate
import ch.epfl.bluebrain.nexus.sourcing.akka.{AkkaAggregate, AkkaSourcingConfig, PassivationStrategy, RetryStrategy}

class Acls[F[_]](agg: Agg[F], index: AclsIndex[F])(implicit F: Monad[F],
                                                   clock: Clock,
                                                   http: HttpConfig,
                                                   initAcl: InitialAcl) {

  /**
    * Overrides ''acl'' on a ''path''.
    *
    * @param path the target path for the ACL
    * @param acl  the identity to permissions mapping to replace
    */
  def replace(path: Path, rev: Long, acl: AccessControlList)(implicit caller: Caller): F[AclMetaOrRejection] =
    evaluate(path, ReplaceAcl(path, acl, rev, clock.instant(), caller.subject))

  /**
    * Appends ''acl'' on a ''path''.
    *
    * @param path the target path for the ACL
    * @param acl  the identity to permissions mapping to append
    */
  def append(path: Path, rev: Long, acl: AccessControlList)(implicit caller: Caller): F[AclMetaOrRejection] =
    evaluate(path, AppendAcl(path, acl, rev, clock.instant(), caller.subject))

  /**
    * Subtracts ''acl'' on a ''path''.
    *
    * @param path the target path for the ACL
    * @param acl  the identity to permissions mapping to subtract
    */
  def subtract(path: Path, rev: Long, acl: AccessControlList)(implicit caller: Caller): F[AclMetaOrRejection] =
    evaluate(path, SubtractAcl(path, acl, rev, clock.instant(), caller.subject))

  /**
    * Delete all ACL on a ''path''.
    *
    * @param path the target path for the ACL
    */
  def delete(path: Path, rev: Long)(implicit caller: Caller): F[AclMetaOrRejection] =
    evaluate(path, DeleteAcl(path, rev, clock.instant(), caller.subject))

  //TODO: When permissions surface API is ready, this method should also check that the permissions provided on the ACLs are valid ones.
  private def evaluate(path: Path, cmd: AclCommand)(implicit caller: Caller): F[AclMetaOrRejection] =
    ancestorsContainsWrite(path).flatMap {
      case true =>
        agg
          .evaluateS(path.asString, cmd)
          .map(_.flatMap {
            case Initial    => Left(AclUnexpectedState(path, "Unexpected initial state"))
            case c: Current => Right(c.toResourceMetadata)
          })
      case false => F.pure(Left(AclUnauthorizedWrite(cmd.path)))
    }

  /**
    * Fetches the entire ACL for a ''path'' on the provided ''rev''.
    *
    * @param path   the target path for the ACL
    * @param rev    the revision to fetch
    * @param self   flag to decide whether or not ACLs of other identities than the provided ones should be included in the response.
    *               This is constrained by the current caller having ''acls/read'' permissions on the provided ''path'' or it's parents
    */
  def fetch(path: Path, rev: Long, self: Boolean)(implicit caller: Caller): F[OptResourceAccessControlList] =
    check(path, fetchUnsafe(path, rev), self)

  /**
    * Fetches the entire ACL for a ''path''.
    *
    * @param path the target path for the ACL
    * @param self flag to decide whether or not ACLs of other identities than the provided ones should be included in the response.
    *             This is constrained by the current caller having ''acls/read'' permissions on the provided ''path'' or it's parents
    */
  def fetch(path: Path, self: Boolean)(implicit caller: Caller): F[OptResourceAccessControlList] =
    check(path, fetchUnsafe(path), self)

  /**
    * Fetches the [[AccessControlLists]] of the provided ''path'' with some filtering options.
    *
    * @param path      the path where the ACLs are going to be looked up
    * @param ancestors flag to decide whether or not ancestor paths should be included in the response
    * @param self      flag to decide whether or not ancestor other identities than the provided ones should be included in the response
    * @param caller    the caller that contains the provided identities
    */
  def list(path: Path, ancestors: Boolean, self: Boolean)(implicit caller: Caller): F[AccessControlLists] =
    index.get(path, ancestors, self)(caller.identities)

  private def check(path: Path, fetched: F[OptResourceAccessControlList], self: Boolean)(
      implicit caller: Caller): F[OptResourceAccessControlList] = {
    fetched flatMap {
      case acls if self                            => F.pure(acls.map(_.map(_.filter(caller.identities))))
      case Some(acls) if containsWrite(acls.value) => F.pure(Some(acls))
      case acls =>
        ancestorsContainsWrite(path.parent).map {
          case true  => acls
          case false => acls.map(_.map(_.filter(caller.identities)))
        }
    }
  }

  private def fetchUnsafe(path: Path): F[OptResourceAccessControlList] =
    agg.currentState(path.asString).map(stateToAcl(path, _))

  private def fetchUnsafe(path: Path, rev: Long): F[OptResourceAccessControlList] =
    agg
      .foldLeft[AclState](path.asString, Initial) {
        case (state, event) if event.rev <= rev => next(state, event)
        case (state, _)                         => state
      }
      .map(stateToAcl(path, _))

  private def stateToAcl(path: Path, state: AclState): OptResourceAccessControlList =
    (state, path) match {
      case (Initial, initAcl.path) => Some(initAcl.acl)
      case (Initial, _)            => None
      case (c: Current, _)         => Some(c.toResource)
    }

  private def containsWrite(acl: AccessControlList)(implicit caller: Caller): Boolean =
    acl.value.exists { case (ident, perms) => caller.identities.contains(ident) && perms.contains(writeAcls) }

  private def ancestorsContainsWrite(path: Path)(implicit caller: Caller): F[Boolean] =
    if (path.isEmpty)
      F.pure(false)
    else
      fetchUnsafe(path).flatMap {
        case Some(acls) if containsWrite(acls.value) => F.pure(true)
        case _ if path == Path./                     => F.pure(false)
        case _                                       => ancestorsContainsWrite(path.parent)
      }
}

object Acls {

  /**
    * Construct an ''Acls'' wrapped on an ''F'' type based on akka clustered [[Aggregate]].
    */
  def apply[F[_]: ConcurrentEffect](index: AclsIndex[F])(implicit cl: Clock = Clock.systemUTC(),
                                                         ac: AppConfig,
                                                         sc: AkkaSourcingConfig,
                                                         as: ActorSystem,
                                                         mt: ActorMaterializer): F[Acls[F]] = {
    val aggF: F[Aggregate[F, String, AclEvent, AclState, AclCommand, AclRejection]] = AkkaAggregate.sharded(
      "acls",
      AclState.Initial,
      next,
      evaluate[F],
      PassivationStrategy.immediately[AclState, AclCommand],
      RetryStrategy.never,
      sc,
      ac.cluster.shards
    )
    aggF.map(new Acls(_, index))
  }

  /**
    * Construct an ''Acls'' wrapped on an ''F'' type based on an in memory [[Aggregate]].
    */
  def inMemory[F[_]: ConcurrentEffect](
      index: AclsIndex[F])(implicit cl: Clock, http: HttpConfig, initAcl: InitialAcl): F[Acls[F]] = {
    val aggF: F[Aggregate[F, String, AclEvent, AclState, AclCommand, AclRejection]] =
      Aggregate.inMemory[F, String]("acls", Initial, next, evaluate[F])
    aggF.map(new Acls(_, index))
  }

  def next(state: AclState, ev: AclEvent): AclState = (state, ev) match {

    case (Initial, AclReplaced(p, acl, 1L, instant, identity)) =>
      Current(p, acl, 1L, instant, instant, identity, identity)

    case (Initial, _) => Initial

    case (c: Current, AclReplaced(p, acl, rev, instant, identity)) =>
      c.copy(p, acl, rev, updatedAt = instant, updatedBy = identity)

    case (c: Current, AclAppended(p, acl, rev, instant, identity)) =>
      c.copy(p, c.acl ++ acl, rev, updatedAt = instant, updatedBy = identity)

    case (c: Current, AclSubtracted(p, acl, rev, instant, identity)) =>
      c.copy(p, c.acl -- acl, rev, updatedAt = instant, updatedBy = identity)

    case (c: Current, AclDeleted(p, rev, instant, identity)) =>
      c.copy(p, AccessControlList.empty, rev, updatedAt = instant, updatedBy = identity)
  }

  def evaluate[F[_]: Async](state: AclState, command: AclCommand): F[EventOrRejection] = {
    val F = implicitly[Async[F]]

    def replaced(c: ReplaceAcl): EventOrRejection = state match {
      case _ if c.acl.hasVoidPermissions  => Left(AclInvalidEmptyPermissions(c.path))
      case Initial if c.rev == 0          => Right(AclReplaced(c.path, c.acl, 1L, c.instant, c.subject))
      case Initial                        => Left(AclIncorrectRev(c.path, c.rev))
      case ss: Current if c.rev != ss.rev => Left(AclIncorrectRev(c.path, c.rev))
      case _: Current                     => Right(AclReplaced(c.path, c.acl, c.rev + 1, c.instant, c.subject))
    }

    def append(c: AppendAcl): EventOrRejection = state match {
      case Initial                                  => Left(AclNotFound(c.path))
      case ss: Current if c.rev != ss.rev           => Left(AclIncorrectRev(c.path, c.rev))
      case _: Current if c.acl.hasVoidPermissions   => Left(AclInvalidEmptyPermissions(c.path))
      case ss: Current if ss.acl ++ c.acl == ss.acl => Left(NothingToBeUpdated(c.path))
      case _: Current                               => Right(AclAppended(c.path, c.acl, c.rev + 1, c.instant, c.subject))
    }

    def subtract(c: SubtractAcl): EventOrRejection = state match {
      case Initial                                  => Left(AclNotFound(c.path))
      case ss: Current if c.rev != ss.rev           => Left(AclIncorrectRev(c.path, c.rev))
      case _: Current if c.acl.hasVoidPermissions   => Left(AclInvalidEmptyPermissions(c.path))
      case ss: Current if ss.acl -- c.acl == ss.acl => Left(NothingToBeUpdated(c.path))
      case _: Current                               => Right(AclSubtracted(c.path, c.acl, c.rev + 1, c.instant, c.subject))
    }

    def delete(c: DeleteAcl): EventOrRejection = state match {
      case Initial                                          => Left(AclNotFound(c.path))
      case ss: Current if c.rev != ss.rev                   => Left(AclIncorrectRev(c.path, c.rev))
      case ss: Current if ss.acl == AccessControlList.empty => Left(AclIsEmpty(c.path))
      case _: Current                                       => Right(AclDeleted(c.path, c.rev + 1, c.instant, c.subject))
    }

    command match {
      case c: ReplaceAcl  => F.pure(replaced(c))
      case c: AppendAcl   => F.pure(append(c))
      case c: SubtractAcl => F.pure(subtract(c))
      case c: DeleteAcl   => F.pure(delete(c))
    }
  }
}