package biz.cebelca.gateway

import biz.cebelca.gateway.graphql.GraphQLAPI
import biz.cebelca.gateway.testkit.GatewaySpecDefault
import zio.*
import zio.http.*
import zio.test.*

/**
 * Exercises the GraphQL layer end-to-end against the live API, including the nested
 * `partners { invoices }` shape that ZQuery batches. Requires CEBELCA_BIZ_API_KEY.
 * Run: `./mill gateway.it`
 */
object GraphQLIntegrationSpec extends GatewaySpecDefault:

  private val layers =
    UserCredentials.liveFromEnvironment ++ Client.default >>> CebelcaAPI.live ++ CebelcaToken.fromEnv

  /** Execute a query string against the interpreter, providing the token from env. */
  private def run(query: String) =
    for
      api    <- ZIO.service[CebelcaAPI]
      token  <- ZIO.service[CebelcaToken]
      interp <- GraphQLAPI.make(api).interpreter
      res    <- interp.provideEnvironment(ZEnvironment(token)).execute(query)
    yield res

  def spec = suite("GraphQL (live)")(
    test("flat: partners returns names") {
      for res <- run("{ partners { id name } }")
      yield assertTrue(res.errors.isEmpty, res.data.toString.contains("Demo Company"))
    },
    test("nested: partners { invoices } resolves invoices per partner (ZQuery-batched)") {
      for res <- run("{ partners { id name invoices { title payment } } }")
      yield
        val out = res.data.toString
        assertTrue(
          res.errors.isEmpty,
          // partner 7 (Kaldi) has real invoices
          out.contains("26-0002"),
          out.contains("26-0001")
        )
    },
    test("nested lookup: partner(value:7) { invoices } has invoices") {
      for res <- run("{ partner(value: 7) { name invoices { title } } }")
      yield assertTrue(res.errors.isEmpty, res.data.toString.contains("26-0001"))
    }
  ).provideShared(layers)
