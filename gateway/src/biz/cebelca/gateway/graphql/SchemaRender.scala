package biz.cebelca.gateway.graphql

import biz.cebelca.gateway.CebelcaAPI
import zio.*
import zio.http.Client

/** Build-time entrypoint: render the GraphQL SDL and write it to the path given as the first arg. Invoked by the
  * `gateway.schemaResource` Mill task so the SDL is baked into the assembly jar (and thus the Docker image) as a
  * classpath resource, served publicly at `/schema.graphql`. Rendering is pure — the `CebelcaAPI` is only needed to
  * construct the resolver, never called — so no network access occurs.
  */
object SchemaRender extends ZIOAppDefault:
  private def program =
    for
      args  <- getArgs
      target = args.headOption.getOrElse("schema.graphql")
      sdl   <- CebelcaAPI.make.map(api => GraphQLAPI.make(api).render)
      _     <- ZIO.writeFile(target, sdl)
      _     <- Console.printLine(s"Rendered GraphQL schema to $target")
    yield ()

  def run = program.provideSome[ZIOAppArgs](Client.default)
