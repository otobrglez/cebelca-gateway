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
    test("nested lookup: partner(id:7) { invoices } has invoices") {
      for res <- run("{ partner(id: 7) { name invoices { title } } }")
      yield assertTrue(res.errors.isEmpty, res.data.toString.contains("26-0001"))
    },
    test("filter: Debtors delegates to the server and is a subset of All") {
      for
        all     <- run("{ partners(filter: All) { id } }")
        debtors <- run("{ partners(filter: Debtors) { id name } }")
      yield
        val idOf = "\"id\":(\\d+)".r
        val allIds     = idOf.findAllMatchIn(all.data.toString).map(_.group(1)).toSet
        val debtorIds  = idOf.findAllMatchIn(debtors.data.toString).map(_.group(1)).toSet
        assertTrue(
          all.errors.isEmpty,
          debtors.errors.isEmpty,
          allIds.nonEmpty,
          debtorIds.subsetOf(allIds)
        )
    },
    test("search: matches partner names server-side") {
      for res <- run("""{ partners(search: "demo") { id name } }""")
      yield
        val out = res.data.toString
        assertTrue(
          res.errors.isEmpty,
          out.contains("Demo Company"),
          // "Kaldi" doesn't match "demo" — the server excludes it
          !out.contains("Kaldi")
        )
    },
    test("services: returns the pricelist") {
      for res <- run("{ services { id title price mu vat } }")
      yield assertTrue(
        res.errors.isEmpty,
        res.data.toString.contains("Management and development")
      )
    },
    test("invoices(dateFrom, dateTo): date range filters server-side") {
      for
        inRange <- run("""{ invoices(dateFrom: "2026-07-20", dateTo: "2026-07-20") { id title } }""")
        empty   <- run("""{ invoices(dateFrom: "2025-01-01", dateTo: "2025-12-31") { id } }""")
      yield
        val idOf = "\"id\":(\\d+)".r
        assertTrue(
          inRange.errors.isEmpty,
          empty.errors.isEmpty,
          inRange.data.toString.contains("26-0001"),
          // outside the window: no rows
          idOf.findFirstIn(empty.data.toString).isEmpty
        )
    },
    test("nested partner.invoices(dateFrom, dateTo): date range filters the nested field") {
      for
        inRange <- run("""{ partner(id: 7) { invoices(dateFrom: "2026-07-20", dateTo: "2026-07-20") { title } } }""")
        empty   <- run("""{ partner(id: 7) { invoices(dateFrom: "2025-01-01", dateTo: "2025-12-31") { title } } }""")
      yield assertTrue(
        inRange.errors.isEmpty,
        empty.errors.isEmpty,
        inRange.data.toString.contains("26-0001"),
        // partner 7 has no 2025 invoices — nested list is empty
        !empty.data.toString.contains("26-0001")
      )
    }
  ).provideShared(layers)
