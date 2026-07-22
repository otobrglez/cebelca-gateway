package biz.cebelca.gateway.graphql

import biz.cebelca.gateway.{CebelcaAPI, CebelcaToken}
import zio.*
import zio.http.*
import caliban.Configurator
import caliban.execution.QueryExecution
import caliban.quick.*

object GraphQLServer:
  private def bearerToken(request: Request): Option[CebelcaToken] =
    request
      .header(Header.Authorization)
      .collect { case Header.Authorization.Bearer(t) => t.stringValue }
      .filter(_.trim.nonEmpty)
      .map(CebelcaToken(_))

  private def isUI(req: Request, graphiqlPath: Option[String]) =
    req.method == Method.GET && graphiqlPath.exists(p => req.path.toString.stripSuffix("/") == p.stripSuffix("/"))

  /** A GraphQL request is "introspection-only" if every operation targets only the meta fields (`__schema` / `__type`)
    * — i.e. it reveals the schema shape but reads no domain data. GraphiQL fires exactly such a query on load.
    * Heuristic: the body mentions `__schema`/`__type` and no top-level domain field. Good enough for a dev gate;
    * introspection exposes only the public API contract, not data.
    */
  private def isIntrospectionOnly(body: String): Boolean =
    val hasMeta    = body.contains("__schema") || body.contains("__type")
    // crude but safe: reject if it also references a known data field
    val dataFields = List("partners", "partner", "invoices")
    hasMeta && !dataFields.exists(body.contains)

  /** Authenticate every request EXCEPT: the GraphiQL UI page (a static GET), and unauthenticated introspection queries
    * (so GraphiQL loads its schema without a token). Real data queries still require a Bearer token. Reading the body
    * here consumes it, so we rebuild the request with the buffered body for downstream.
    */
  private def authGate(graphiqlPath: Option[String]): HandlerAspect[Any, CebelcaToken] =
    HandlerAspect.interceptIncomingHandler(
      Handler.fromFunctionZIO[Request] { req =>
        if isUI(req, graphiqlPath) then ZIO.succeed(req -> CebelcaToken.empty)
        else
          bearerToken(req) match
            case Some(token) => ZIO.succeed(req -> token)
            case None        =>
              req.body.asString.orElseSucceed("").flatMap { raw =>
                if isIntrospectionOnly(raw) then ZIO.succeed(req.withBody(Body.fromString(raw)) -> CebelcaToken.empty)
                else ZIO.fail(Response.unauthorized("missing 'Authorization: Bearer <cebelca-token>' header"))
              }
      }
    )
  
  /** Liveness/readiness probe for Kubernetes. Deliberately trivial — the gateway is stateless, so a plain `200 ok`
    * confirms the process is up and accepting connections. Kept outside `authGate` so probes need no Bearer token.
    */
  private val healthRoute: Routes[Any, Response] =
    Routes(Method.GET / "health" -> Handler.fromResponse(Response.text("ok")))

  /** Serve the GraphQL SDL publicly (no auth) at `/schema.graphql`. The file is baked into the assembly jar as a
    * classpath resource at build time (see `gateway.schemaResource` in build.mill), so it always matches the shipped
    * server. Falls back to a 404 if the resource is somehow absent (e.g. run outside the packaged jar).
    */
  private val schemaRoute: Routes[Any, Response] =
    Routes(
      Method.GET / "schema.graphql" -> Handler.fromZIO {
        ZIO
          .attemptBlocking(Option(getClass.getResourceAsStream("/schema.graphql")))
          .some
          .flatMap(is => ZIO.attemptBlocking(scala.io.Source.fromInputStream(is)(using scala.io.Codec.UTF8).mkString))
          .fold(
            _ => Response.notFound("schema not available"),
            sdl => Response.text(sdl).addHeader(Header.ContentType(MediaType.text.plain))
          )
      }
    )

  private def rootRedirect(graphiqlPath: Option[String]): Routes[Any, Response] =
    graphiqlPath match
      case Some(path) =>
        val target = URL(Path.decode(path))
        Routes(Method.GET / Root -> Handler.fromResponse(Response.seeOther(target)))
      case None       => Routes.empty

  private def routes(
    apiPath: String,
    graphiqlPath: Option[String]
  )(api: CebelcaAPI): UIO[Routes[Any, Response]] =
    GraphQLAPI
      .make(api)
      .routes(apiPath = apiPath, graphiqlPath = graphiqlPath)
      .orDie
      .map(gql => healthRoute ++ schemaRoute ++ rootRedirect(graphiqlPath) ++ (gql @@ authGate(graphiqlPath)))

  def serve(port: Int, apiPath: String, graphiqlPath: Option[String]): RIO[Scope & CebelcaAPI, Nothing] = for
    _       <- Configurator.setQueryExecution(QueryExecution.Batched)
    rs      <- ZIO.serviceWithZIO[CebelcaAPI](routes(apiPath, graphiqlPath))
    _       <- ZIO.logInfo(s"GraphQL serving on :$port$apiPath")
    nothing <- Server.serve(rs).provide(Server.defaultWithPort(port))
  yield nothing
