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
        // structural, not tied to a specific live row: at least one service with a non-empty title
        "\"title\":\"[^\"]+\"".r.findFirstIn(res.data.toString).isDefined
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
    },
    test("invoices(filter: Paid): top-level status filter") {
      for
        paid <- run("{ invoices(filter: Paid) { id payment } }")
        all  <- run("{ invoices(filter: All) { id } }")
      yield
        val idOf = "\"id\":(\\d+)".r
        val paidIds = idOf.findAllMatchIn(paid.data.toString).map(_.group(1)).toSet
        val allIds  = idOf.findAllMatchIn(all.data.toString).map(_.group(1)).toSet
        assertTrue(paid.errors.isEmpty, all.errors.isEmpty, allIds.nonEmpty, paidIds.subsetOf(allIds))
    },
    test("nested partner.invoices(filter: Paid): status filter works on the nested field") {
      for res <- run("{ partner(id: 7) { invoices(filter: Paid) { id title } } }")
      yield
        // partner 7's paid set includes 26-0002 (paid) and excludes 26-0001 (unpaid)
        val out = res.data.toString
        assertTrue(res.errors.isEmpty, out.contains("26-0002"), !out.contains("26-0001"))
    },
    test("paid/datePaid reflect real status (not the misleading `payment` terms field)") {
      for
        paid   <- run("{ invoices(filter: Paid) { paid datePaid } }")
        unpaid <- run("{ invoices(filter: Unpaid) { paid datePaid } }")
      yield assertTrue(
        paid.errors.isEmpty,
        unpaid.errors.isEmpty,
        // every Paid invoice reports paid:true with a date; every Unpaid reports paid:false/null
        paid.data.toString.contains("\"paid\":true"),
        !paid.data.toString.contains("\"paid\":false"),
        unpaid.data.toString.contains("\"paid\":false"),
        !unpaid.data.toString.contains("\"paid\":true")
      )
    },
    test("mutations: createService → updateService → deleteService round-trip (self-cleaning)") {
      // Create via the mutation, capturing the id so cleanup is guaranteed even if a later assertion fails.
      ZIO.acquireReleaseWith(
        run(s"""mutation { createService(input: {title: "${Marker}GQL", price: 5, mu: "kos", vat: 22}) { id title price } }""")
      )(created => idFrom(created).fold(ZIO.unit)(id => CebelcaAPI.deleteService(id).ignore)) { created =>
        val id = idFrom(created).getOrElse(-1L)
        for
          updated <- run(s"""mutation { updateService(id: $id, input: {title: "${Marker}GQL2", price: 7, mu: "ura", vat: 9.5}) { id title price mu } }""")
          deleted <- run(s"mutation { deleteService(id: $id) }")
          after   <- run("{ services { id } }")
        yield assertTrue(
          created.errors.isEmpty,
          id > 0,
          updated.errors.isEmpty,
          updated.data.toString.contains(s"${Marker}GQL2"),
          updated.data.toString.contains("\"mu\":\"ura\""),
          deleted.errors.isEmpty,
          deleted.data.toString.contains("\"deleteService\":true"),
          !after.data.toString.contains(s"\"id\":$id")
        )
      }
    },
    test("mutations: createPartner → updatePartner → deletePartner round-trip (self-cleaning)") {
      ZIO.acquireReleaseWith(
        run(s"""mutation { createPartner(input: {name: "${Marker}P", city: "Ljubljana", vatid: "SI22222222"}) { id name city vatid } }""")
      )(created => idFrom(created).fold(ZIO.unit)(id => CebelcaAPI.deletePartner(id).ignore)) { created =>
        val id = idFrom(created).getOrElse(-1L)
        for
          updated <- run(s"""mutation { updatePartner(id: $id, input: {name: "${Marker}P2", city: "Maribor"}) { id name city } }""")
          deleted <- run(s"mutation { deletePartner(id: $id) }")
          after   <- run(s"""{ partners(search: "$Marker") { id } }""")
        yield assertTrue(
          created.errors.isEmpty,
          id > 0,
          created.data.toString.contains("\"vatid\":\"SI22222222\""),
          updated.errors.isEmpty,
          updated.data.toString.contains(s"${Marker}P2"),
          updated.data.toString.contains("\"city\":\"Maribor\""),
          deleted.errors.isEmpty,
          deleted.data.toString.contains("\"deletePartner\":true"),
          !after.data.toString.contains(s"\"id\":$id")
        )
      }
    }
  ).provideShared(layers)

  // No underscore: the API's `search` treats `_` as a SQL LIKE wildcard. A distinct alphabetic marker keeps this
  // suite's rows from matching the API suite's marker (both run in parallel against the same account).
  private val Marker = "ZZZGQLTEST"

  /** Pull `createService.id` out of a GraphQL response, if present. */
  private def idFrom(res: caliban.GraphQLResponse[Any]): Option[Long] =
    "\"id\":(\\d+)".r.findFirstMatchIn(res.data.toString).map(_.group(1).toLong)
