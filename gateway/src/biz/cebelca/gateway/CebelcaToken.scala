package biz.cebelca.gateway

import zio.*

/** The outbound credential used to authenticate the gateway against cebelca.biz. It is a *request-scoped* value:
  * provided once at startup for single-tenant use (CLI), or per-request for multi-tenant use (GraphQL), where each
  * inbound identity maps to a different cebelca account. Keeping it in the environment — rather than frozen into
  * `CebelcaAPI` at construction — is what lets the same service serve both models without change.
  */
final case class CebelcaToken(value: String)

object CebelcaToken:
  /** Single-tenant: derive the token once from the environment credentials. */
  val fromEnv: ZLayer[UserCredentials, Throwable, CebelcaToken] =
    ZLayer.fromZIO(UserCredentials.apiToken.map(CebelcaToken(_)))

  val empty: CebelcaToken = CebelcaToken("")

  def mapZIO[A, R, B](action: CebelcaToken => ZIO[A, R, B]): ZIO[A & CebelcaToken, R, B] =
    ZIO.serviceWithZIO[CebelcaToken](action)
