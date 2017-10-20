package ch.epfl.bluebrain.nexus.iam.service.routes

import akka.http.scaladsl.model._
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.directives.Credentials
import ch.epfl.bluebrain.nexus.iam.core.acls._
import ch.epfl.bluebrain.nexus.iam.core.acls.Permissions._
import ch.epfl.bluebrain.nexus.iam.core.auth._
import ch.epfl.bluebrain.nexus.iam.core.identity.Identity
import ch.epfl.bluebrain.nexus.iam.core.identity.Identity._
import ch.epfl.bluebrain.nexus.iam.service.auth.DownstreamAuthClient
import ch.epfl.bluebrain.nexus.iam.service.directives.AclDirectives._
import ch.epfl.bluebrain.nexus.iam.service.routes.AclsRoutes._
import ch.epfl.bluebrain.nexus.iam.service.routes.CommonRejections._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.Decoder
import io.circe.generic.auto._
import kamon.akka.http.KamonTraceDirectives.traceName

import scala.concurrent.{ExecutionContext, Future}

/**
  * HTTP routes for ACL specific functionality.
  *
  * @param acl  the ACL operations bundle
  * @param dsac the downstream authentication client
  */
class AclsRoutes(acl: Acls[Future], dsac: DownstreamAuthClient[Future]) extends DefaultRoutes("acls") {

  override def apiRoutes: Route = {
    implicit val caller: Identity = Anonymous

    extractExecutionContext { implicit ec =>
      extractResourcePath { path =>
        authenticateOAuth2Async("*", authenticator).withAnonymousUser(AnonymousUser) { user =>
          put {
            entity(as[AccessControlList]) { list =>
              authorizeAsync(check(path, user, Permission.Own)) {
                traceName("createPermissions") {
                  onSuccess(acl.create(path, list)) {
                    complete(StatusCodes.Created)
                  }
                }
              }
            }
          } ~
            post {
              entity(as[AccessControl]) { ac =>
                authorizeAsync(check(path, user, Permission.Own)) {
                  traceName("addPermisssions") {
                    onSuccess(acl.add(path, ac.identity, ac.permissions)) { result =>
                      complete(StatusCodes.OK -> AccessControl(ac.identity, result))
                    }
                  }
                }
              }
            } ~
            delete {
              authorizeAsync(check(path, user, Permission.Own)) {
                traceName("deletePermissions") {
                  onSuccess(acl.clear(path)) {
                    complete(StatusCodes.NoContent)
                  }
                }
              }
            } ~
            get {
              parameters('all.as[Boolean].?) {
                case Some(true) =>
                  authorizeAsync(check(path, user, Permission.Own)) {
                    traceName("getAllPermissions") {
                      onSuccess(acl.fetch(path)) { result =>
                        complete(StatusCodes.OK -> AccessControlList.fromMap(result))
                      }
                    }
                  }
                case _ =>
                  authorizeAsync(check(path, user, Permission.Read)) {
                    traceName("getPermissions") {
                      onSuccess(acl.retrieve(path, user.identities)) { result =>
                        complete(StatusCodes.OK -> AccessControlList.fromMap(result))
                      }
                    }
                  }
              }
            }
        }
      }
    }
  }

  private def authenticator(implicit ec: ExecutionContext): AsyncAuthenticator[User] = {
    case Credentials.Missing         => Future.successful(None)
    case Credentials.Provided(token) => dsac.getUser(token).map(Option.apply)
  }

  private def check(path: Path, user: User, permission: Permission)(implicit ec: ExecutionContext): Future[Boolean] =
    acl.retrieve(path, user.identities).map(_.values.exists(_.contains(permission)))

}

object AclsRoutes {

  def apply(acl: Acls[Future], dsac: DownstreamAuthClient[Future]): AclsRoutes = new AclsRoutes(acl, dsac)

  implicit val decoder: Decoder[AccessControl] = Decoder.instance { cursor =>
    val fields = cursor.fields.toSeq.flatten
    if (!fields.contains("permissions"))
      throw WrongOrInvalidJson(Some("Missing field 'permissions' in payload"))
    else if (!fields.contains("identity"))
      throw WrongOrInvalidJson(Some("Missing field 'identity' in payload"))
    else
      cursor.downField("permissions").as[Permissions] match {
        case Left(df) => throw IllegalPermissionString(df.message)
        case Right(permissions) =>
          cursor.downField("identity").as[Identity] match {
            case Left(df)        => throw IllegalIdentityFormat(df.message, "identity")
            case Right(identity) => Right(AccessControl(identity, permissions))
          }
      }
  }
}