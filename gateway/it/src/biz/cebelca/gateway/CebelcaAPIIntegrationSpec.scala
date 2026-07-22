package biz.cebelca.gateway

import biz.cebelca.gateway.testkit.GatewaySpecDefault
import zio.*
import zio.http.*
import zio.test.*
import zio.test.Assertion.*

object CebelcaAPIIntegrationSpec extends GatewaySpecDefault:
  private val layers =
    UserCredentials.liveFromEnvironment ++ Client.default >>> CebelcaAPI.live ++ CebelcaToken.fromEnv

  def spec = suite("CebelcaAPI (live)")(
    test("partners: select-all returns decoded rows") {
      for partners <- CebelcaAPI.partners
      yield assertTrue(partners.nonEmpty)
    },
    test("invoices: select-all returns decoded heads") {
      for invoices <- CebelcaAPI.invoices
      yield assertTrue(invoices.forall(_.id_partner >= 0))
    },
    test("explore mode lists resources") {
      for
        api  <- ZIO.service[CebelcaAPI]
        rows <- api.query[ExploreEntry](Cmd.exploreResources)
      yield assertTrue(rows.exists(_.name == "invoice-sent"), rows.exists(_.name == "partner"))
    },
    test("items: select-all decodes into Item") {
      for
        api   <- ZIO.service[CebelcaAPI]
        items <- api.query[Item](Cmd.select("item"))
      yield assertTrue(items.forall(_.price >= 0))
    },
    test("partnersFiltered(debtors): server-side filter returns a subset of all partners") {
      for
        all     <- CebelcaAPI.partnersFiltered("all")
        debtors <- CebelcaAPI.partnersFiltered("debtors")
      yield
        val allIds     = all.map(_.id).toSet
        val debtorIds  = debtors.map(_.id).toSet
        assertTrue(
          all.nonEmpty,
          // the server does the debt selection: debtors are strictly a subset of all
          debtorIds.subsetOf(allIds),
          debtors.size <= all.size
        )
    },
    test("invoicesBetween: date bounds filter server-side on date_sent") {
      for
        all      <- CebelcaAPI.invoicesBetween(None, None)
        inRange  <- CebelcaAPI.invoicesBetween(Some("2026-07-20"), Some("2026-07-20"))
        empty    <- CebelcaAPI.invoicesBetween(Some("2025-01-01"), Some("2025-12-31"))
      yield assertTrue(
        all.nonEmpty,
        inRange.nonEmpty,
        inRange.forall(_.date_sent == "2026-07-20"),
        inRange.size <= all.size,
        empty.isEmpty
      )
    },
    test("services: select-all decodes invoice-sent-o pricelist rows") {
      for services <- CebelcaAPI.services
      yield assertTrue(
        services.nonEmpty,
        services.forall(s => s.price >= 0 && s.object_title.nonEmpty)
      )
    },
    test("partnersFiltered(search): case-insensitive substring match on name, server-side") {
      for
        all      <- CebelcaAPI.partnersFiltered("all")
        matched  <- CebelcaAPI.partnersFiltered("all", Some("demo"))
        upper    <- CebelcaAPI.partnersFiltered("all", Some("DEMO"))
      yield assertTrue(
        matched.nonEmpty,
        matched.size < all.size,
        matched.forall(_.name.toLowerCase.contains("demo")),
        // case-insensitive: "demo" and "DEMO" return the same rows
        matched.map(_.id).toSet == upper.map(_.id).toSet
      )
    }
    /*
    test("validation error: empty invoice insert surfaces CebelcaError.Validation") {
      for
        api    <- ZIO.service[CebelcaAPI]
        result <- api.query[InvoiceHead](Cmd.select(resource = "invoice-sent", method = "insert-into")).exit
      yield assert(result)(
        fails(isSubtype[CebelcaError.Validation](hasField("fields", _.fields, isNonEmpty)))
      )
    }
     */
  ).provideShared(layers)

  // Minimal decoder for explore-mode rows. Some descriptions are null.
  final case class ExploreEntry(name: String, description: Option[String]) derives zio.json.JsonDecoder
