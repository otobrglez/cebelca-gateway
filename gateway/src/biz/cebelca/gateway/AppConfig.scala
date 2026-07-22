package biz.cebelca.gateway

import zio.*
import zio.config.*
import zio.Config, Config.*

type Port = Int

case class AppConfig(
  port: Port
)

object AppConfig:
  val configDef: Config[AppConfig] = int("PORT").to[AppConfig]
  def config: IO[Error, AppConfig] = ZIO.config(configDef)
  def port: IO[Error, Port]        = config.map(_.port)
