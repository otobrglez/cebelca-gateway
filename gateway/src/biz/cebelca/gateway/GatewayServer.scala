package biz.cebelca.gateway

import zio.*
import zio.Runtime.{enableRuntimeMetrics, removeDefaultLoggers, setConfigProvider}
import zio.http.*
import zio.logging.backend.SLF4J
import zio.metrics.connectors.{prometheus, MetricsConfig}
import zio.metrics.jvm.DefaultJvmMetrics

object GatewayServer extends ZIOAppDefault:
  override val bootstrap =
    setConfigProvider(ConfigProvider.envProvider) >>> removeDefaultLoggers >>> SLF4J.slf4j

  private val metricsConfig = ZLayer.succeed(MetricsConfig(5.seconds))

  private def program = for
    _           <- ZIO.log("Starting gateway server")
    port        <- AppConfig.port
    metricsPort <- AppConfig.metricsPort
    _           <- graphql.MetricsServer.serve(metricsPort).forkScoped
    _           <-
      graphql.GraphQLServer.serve(
        port = port,
        apiPath = "/api/graphql",
        graphiqlPath = Some("/graphiql")
      )
  yield ()

  def run = program.provide(
    Scope.default,
    Client.default,
    CebelcaAPI.live,
    // metrics
    metricsConfig,
    prometheus.publisherLayer,
    prometheus.prometheusLayer,
    enableRuntimeMetrics,
    DefaultJvmMetrics.liveV2.unit
  )
