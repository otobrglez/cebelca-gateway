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
    test("invoicesBy: date bounds filter server-side on date_sent") {
      for
        all      <- CebelcaAPI.invoicesBy()
        inRange  <- CebelcaAPI.invoicesBy(dateFrom = Some("2026-07-20"), dateTo = Some("2026-07-20"))
        empty    <- CebelcaAPI.invoicesBy(dateFrom = Some("2025-01-01"), dateTo = Some("2025-12-31"))
      yield assertTrue(
        all.nonEmpty,
        inRange.nonEmpty,
        inRange.forall(_.date_sent == "2026-07-20"),
        inRange.size <= all.size,
        empty.isEmpty
      )
    },
    test("invoicesBy: status filter selects server-side subsets") {
      for
        all     <- CebelcaAPI.invoicesBy("all")
        paid    <- CebelcaAPI.invoicesBy("payed")
        unpaid  <- CebelcaAPI.invoicesBy("unpayed")
      yield
        val allIds = all.map(_.id).toSet
        assertTrue(
          all.nonEmpty,
          paid.map(_.id).toSet.subsetOf(allIds),
          unpaid.map(_.id).toSet.subsetOf(allIds),
          // paid and unpaid are disjoint partitions of the invoice set
          paid.map(_.id).toSet.intersect(unpaid.map(_.id).toSet).isEmpty
        )
    },
    test("services: select-all decodes invoice-sent-o pricelist rows") {
      for services <- CebelcaAPI.services
      yield assertTrue(
        services.nonEmpty,
        services.forall(s => s.price >= 0 && s.object_title.nonEmpty)
      )
    },
    test("servicesFiltered(search): server-side substring match (via select-all-by, not select-all)") {
      // create two distinct services so search must discriminate; guaranteed cleanup on any exit.
      ZIO.acquireReleaseWith(
        CebelcaAPI.createService(s"${Marker}alpha_widget", 10, "kos", 22, "", "") <*>
          CebelcaAPI.createService(s"${Marker}beta_gadget", 20, "kos", 22, "", "")
      ) { case (a, b) => CebelcaAPI.deleteService(a.id).ignore *> CebelcaAPI.deleteService(b.id).ignore } {
        case (a, b) =>
          for
            all     <- CebelcaAPI.servicesFiltered(None)
            widget  <- CebelcaAPI.servicesFiltered(Some("alpha_widget"))
            upper   <- CebelcaAPI.servicesFiltered(Some("ALPHA_WIDGET"))
            none    <- CebelcaAPI.servicesFiltered(Some("nomatch_zzz_qqq"))
          yield assertTrue(
            // both created services are visible unfiltered
            all.exists(_.id == a.id) && all.exists(_.id == b.id),
            // search narrows to just the matching one — proving it filters server-side (the bug was: it did not)
            widget.exists(_.id == a.id),
            !widget.exists(_.id == b.id),
            widget.size < all.size,
            // case-insensitive
            upper.map(_.id).toSet == widget.map(_.id).toSet,
            // no false matches
            none.isEmpty
          )
      }
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
    },
    test("services CRUD: create → update → delete round-trip (self-cleaning)") {
      // acquireReleaseWith guarantees the created service is deleted even if an assertion fails or the effect dies.
      ZIO.acquireReleaseWith(
        CebelcaAPI.createService(s"${Marker}CREATE", 42.5, "kos", 22.0, "", "")
      )(created => CebelcaAPI.deleteService(created.id).ignore) { created =>
        for
          updated <- CebelcaAPI.updateService(created.id, s"${Marker}UPDATE", 99.9, "ura", 9.5, "", "")
          deleted <- CebelcaAPI.deleteService(created.id)
          after   <- CebelcaAPI.services
        yield assertTrue(
          created.object_title == s"${Marker}CREATE",
          created.price == 42.5,
          created.id > 0,
          updated.id == created.id,
          updated.object_title == s"${Marker}UPDATE",
          updated.price == 99.9,
          updated.measure_unit == "ura",
          deleted,
          !after.exists(_.id == created.id)
        )
      }
    },
    // Runs last (suite is sequential): sweep any services left over from an interrupted run and assert none remained.
    test("services cleanup sweep: no test residue survives") {
      for
        orphans <- CebelcaAPI.services.map(_.filter(_.object_title.startsWith(Marker)))
        _       <- ZIO.foreachDiscard(orphans)(o => CebelcaAPI.deleteService(o.id).ignore)
      yield assertTrue(orphans.isEmpty)
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

  /** Prefix marking services created by this suite, so any orphan from a hard crash is identifiable and sweepable. */
  private val Marker = "ZZZ_IT_"

  // Minimal decoder for explore-mode rows. Some descriptions are null.
  final case class ExploreEntry(name: String, description: Option[String]) derives zio.json.JsonDecoder
