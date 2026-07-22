package biz.cebelca.gateway

import zio.*

type Username = String
type Password = String
type APIToken = String

final case class UserCredentials private (
  private val maybeUsername: Option[Username],
  private val maybePassword: Option[Password],
  private val maybeAPIToken: Option[APIToken]
)
object UserCredentials:
  private def fromEnvironment = for
    maybeUsername <-
      zio.System.env("CEBELCA_DEMO_USER").orElse(zio.System.env("CEBELCA_USERNAME")).map(_.filter(_.nonEmpty))
    maybePassword <-
      zio.System.env("CEBELCA_DEMO_PASS").orElse(zio.System.env("CEBELCA_PASSWORD")).map(_.filter(_.nonEmpty))
    maybeAPIToken <- zio.System.env("CEBELCA_BIZ_API_KEY").map(_.filter(_.nonEmpty))
  yield UserCredentials(maybeUsername, maybePassword, maybeAPIToken)

  def liveFromEnvironment: TaskLayer[UserCredentials] =
    ZLayer.fromZIO(fromEnvironment.absorbWith(_ => new RuntimeException("Failed getting env variable.")))

  def apiToken: RIO[UserCredentials, APIToken] =
    ZIO
      .serviceWith[UserCredentials](_.maybeAPIToken)
      .flatMap(ZIO.getOrFailWith(new RuntimeException("API token is not set")))
