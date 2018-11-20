package ch.epfl.bluebrain.nexus.iam

import ch.epfl.bluebrain.nexus.iam.config.AppConfig.HttpConfig
import ch.epfl.bluebrain.nexus.iam.config.Vocabulary._
import ch.epfl.bluebrain.nexus.iam.types.{Permission, ResourceF, ResourceMetadata}
import ch.epfl.bluebrain.nexus.rdf.Iri
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.Iri.Path._
import ch.epfl.bluebrain.nexus.rdf.Vocabulary._
import ch.epfl.bluebrain.nexus.sourcing.Aggregate

package object acls {

  def base(implicit http: HttpConfig): Iri.AbsoluteIri = http.publicIri + (http.prefix / "acls")

  val types: Set[AbsoluteIri] = Set(nxv.AccessControlList)

  val writeAcls = Permission.unsafe("acls/write")

  type Agg[F[_]]                    = Aggregate[F, String, AclEvent, AclState, AclCommand, AclRejection]
  type EventOrRejection             = Either[AclRejection, AclEvent]
  type AclMetaOrRejection           = Either[AclRejection, ResourceMetadata]
  type ResourceAccessControlList    = ResourceF[AccessControlList]
  type OptResourceAccessControlList = Option[ResourceF[AccessControlList]]

}