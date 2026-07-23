package biz.cebelca.gateway.graphql

import biz.cebelca.gateway.CebelcaAPI
import zio.*
import zio.http.Client
import zio.Runtime.{removeDefaultLoggers, setConfigProvider}
import java.nio.file.Path

object SchemaRender extends ZIOAppDefault:
  override val bootstrap = setConfigProvider(ConfigProvider.envProvider) >>> removeDefaultLoggers

  private def program = for
    args  <- getArgs
    target = args.headOption.getOrElse("schema.graphql")
    path   = Path.of(target)
    sdl   <- CebelcaAPI.make.map(api => GraphQLAPI.make(api).render)
    _     <- ZIO.writeFile(path.toAbsolutePath, sdl)
    _     <- Console.printLine(s"Rendered GraphQL schema to ${path.toAbsolutePath}")
  yield ()

  def run = program.provideSome[ZIOAppArgs](Client.default)
