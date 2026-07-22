package biz.cebelca.gateway

import zio.*
import zio.config.*
import zio.Config, Config.*

type Port = Int

final case class AppConfig(
  port: Port,
  metricsPort: Port
)

object AppConfig:
  private val defaultMetricsPort: Port     = 9090
  private val configDef: Config[AppConfig] =
    (int("PORT") ++ int("METRICS_PORT").withDefault(defaultMetricsPort)).to[AppConfig]
  private def config: IO[Error, AppConfig] = ZIO.config(configDef)
  def port: IO[Error, Port]                = config.map(_.port)
  def metricsPort: IO[Error, Port]         = config.map(_.metricsPort)
