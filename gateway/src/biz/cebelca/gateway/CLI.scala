package biz.cebelca.gateway

import zio.*
import zio.Runtime.{removeDefaultLoggers, setConfigProvider}
import zio.http.*
import zio.logging.backend.SLF4J

object CLI extends ZIOAppDefault:
  override val bootstrap =
    setConfigProvider(ConfigProvider.envProvider) >>> removeDefaultLoggers >>> SLF4J.slf4j

  private def program = for
    _    <- ZIO.log("Running server via CLI")
    port <- AppConfig.port
    _    <-
      graphql.GraphQLServer.serve(
        port = port,
        apiPath = "/api/graphql",
        graphiqlPath = Some("/graphiql")
      )
  yield ()

  def run = program.provide(
    Scope.default,
    Client.default,
    CebelcaAPI.live
  )
