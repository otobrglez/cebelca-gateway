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
      for services <- CebelcaAPI.services()
      yield assertTrue(
        services.nonEmpty,
        services.forall(s => s.price >= 0 && s.object_title.nonEmpty)
      )
    },
    test("servicesFiltered(search): server-side substring match (via select-all-by, not select-all)") {
      // create two distinct services so search must discriminate; guaranteed cleanup on any exit.
      ZIO.acquireReleaseWith(
        CebelcaAPI.createService(ServiceFields(s"${Marker}alpha_widget", 10, "kos", 22, "", "")) <*>
          CebelcaAPI.createService(ServiceFields(s"${Marker}beta_gadget", 20, "kos", 22, "", ""))
      ) { case (a, b) => CebelcaAPI.deleteService(a.id).ignore *> CebelcaAPI.deleteService(b.id).ignore } {
        case (a, b) =>
          for
            all     <- CebelcaAPI.services(None)
            widget  <- CebelcaAPI.services(Some("alpha_widget"))
            upper   <- CebelcaAPI.services(Some("ALPHA_WIDGET"))
            none    <- CebelcaAPI.services(Some("nomatch_zzz_qqq"))
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
        CebelcaAPI.createService(ServiceFields(s"${Marker}CREATE", 42.5, "kos", 22.0, "", ""))
      )(created => CebelcaAPI.deleteService(created.id).ignore) { created =>
        for
          updated <- CebelcaAPI.updateService(created.id, ServiceFields(s"${Marker}UPDATE", 99.9, "ura", 9.5, "", ""))
          deleted <- CebelcaAPI.deleteService(created.id)
          after   <- CebelcaAPI.services()
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
    test("partners CRUD: create → update → delete round-trip (self-cleaning)") {
      ZIO.acquireReleaseWith(
        CebelcaAPI.createPartner(PartnerFields(s"${Marker}CREATE", city = "Ljubljana", vatid = "SI11111111", country = "SI"))
      )(created => CebelcaAPI.deletePartner(created.id).ignore) { created =>
        for
          updated <- CebelcaAPI.updatePartner(created.id, PartnerFields(s"${Marker}UPDATE", city = "Maribor"))
          deleted <- CebelcaAPI.deletePartner(created.id)
          after   <- CebelcaAPI.partnersFiltered("all", Some(Marker))
        yield assertTrue(
          created.name == s"${Marker}CREATE",
          created.city == "Ljubljana",
          created.vatid == "SI11111111",
          created.id > 0,
          updated.id == created.id,
          updated.name == s"${Marker}UPDATE",
          updated.city == "Maribor",
          deleted,
          !after.exists(_.id == created.id)
        )
      }
    },
    test("recordPayment: records a payment against an invoice (self-cleaning invoice + payment)") {
      // A payment needs an invoice, so create a throwaway one first; nested acquireRelease deletes both on any exit.
      def newInvoice = ZIO.serviceWithZIO[CebelcaAPI](
        _.queryFirst[IdRow](Cmd.insert("invoice-sent", "date_sent" -> "2026-07-23", "date_to_pay" -> "2026-07-30", "id_partner" -> "7"))
      )
      def delInvoice(id: Long) = ZIO.serviceWithZIO[CebelcaAPI](_.ack(Cmd.delete("invoice-sent", id))).ignore
      ZIO.acquireReleaseWith(newInvoice)(inv => delInvoice(inv.id)) { inv =>
        ZIO.acquireReleaseWith(
          CebelcaAPI.recordPayment(PaymentFields(inv.id, "2026-07-23", 123.45, note = s"${Marker}pay"))
        )(pay => CebelcaAPI.deletePayment(pay.id).ignore) { pay =>
          for reread <- CebelcaAPI.payment(pay.id)
          yield assertTrue(
            pay.id > 0,
            pay.id_invoice_sent == inv.id,
            pay.amount == 123.45,
            pay.date_of == "2026-07-23",
            pay.id_payment_method == 1, // defaulted
            pay.note == s"${Marker}pay",
            reread.id == pay.id,
            reread.amount == 123.45
          )
        }
      }
    },
    test("invoice CRUD: create (inline lines) → add/update/delete line → finalize → delete (self-cleaning)") {
      ZIO.acquireReleaseWith(
        CebelcaAPI.createInvoice(
          InvoiceFields("2026-07-23", "2026-07-30", 7),
          List(LineFields("Consulting", 10, 100, 22), LineFields("Setup", 1, 50, 22))
        )
      )(inv => CebelcaAPI.deleteInvoice(inv.id).ignore) { inv =>
        for
          // ISO→SI conversion regression: the date must be stored, not silently emptied
          created  <- CebelcaAPI.invoice(inv.id)
          lineId   <- CebelcaAPI.addLine(inv.id, LineFields("Extra", 2, 5, 22))
          updated  <- CebelcaAPI.updateLine(lineId, inv.id, LineFields("Extra renamed", 3, 7, 9.5))
          _        <- CebelcaAPI.deleteLine(lineId)
          finalized <- CebelcaAPI.finalizeInvoice(inv.id)
        yield assertTrue(
          inv.id > 0,
          created.date_sent == "2026-07-23", // NOT empty — the conversion worked
          created.id_partner == 7,
          created.title.isEmpty,             // draft has no number yet
          updated.id == lineId,
          updated.title == "Extra renamed",
          updated.qty == 3.0,
          finalized.title.nonEmpty           // finalization assigned a number
        )
      }
    },
    test("proposedInvoiceTitle: returns the recommended next number for a year") {
      for title <- CebelcaAPI.proposedInvoiceTitle(2026)
      yield assertTrue(title.nonEmpty, title.startsWith("26-"))
    },
    test("finalizeInvoice: an explicit title overrides the auto-assigned number (self-cleaning)") {
      val custom = "26-ITTEST-1"
      ZIO.acquireReleaseWith(
        CebelcaAPI.createInvoice(InvoiceFields("2026-07-23", "2026-07-30", 7), Nil)
      )(inv => CebelcaAPI.deleteInvoice(inv.id).ignore) { inv =>
        for finalized <- CebelcaAPI.finalizeInvoice(inv.id, title = Some(custom))
        yield assertTrue(finalized.title == custom)
      }
    },
    test("duplicateInvoice: copies lines into a new draft (self-cleaning source + dup)") {
      ZIO.acquireReleaseWith(
        CebelcaAPI.createInvoice(InvoiceFields("2026-07-23", "2026-07-30", 7), List(LineFields("Consulting", 10, 100, 22)))
      )(src => CebelcaAPI.deleteInvoice(src.id).ignore) { src =>
        ZIO.acquireReleaseWith(CebelcaAPI.duplicateInvoice(src.id))(dup => CebelcaAPI.deleteInvoice(dup.id).ignore) { dup =>
          for lines <- CebelcaAPI.invoiceLines.map(_.filter(_.id_invoice_sent == dup.id))
          yield assertTrue(
            dup.id != src.id,
            dup.id_partner == 7,
            dup.title.isEmpty,               // the duplicate is a fresh draft
            lines.exists(_.title == "Consulting") // lines were copied
          )
        }
      }
    },
    // Runs last (suite is sequential): sweep any services/partners left over from an interrupted run; assert none.
    test("cleanup sweep: no test residue survives") {
      for
        svcOrphans <- CebelcaAPI.services().map(_.filter(_.object_title.startsWith(Marker)))
        _          <- ZIO.foreachDiscard(svcOrphans)(o => CebelcaAPI.deleteService(o.id).ignore)
        ptrOrphans <- CebelcaAPI.partnersFiltered("all", Some(Marker))
        _          <- ZIO.foreachDiscard(ptrOrphans)(o => CebelcaAPI.deletePartner(o.id).ignore)
      yield assertTrue(svcOrphans.isEmpty, ptrOrphans.isEmpty)
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

  /** Prefix marking services/partners created by this suite, so any orphan from a hard crash is identifiable and
    * sweepable. No underscore: the API's `search` treats `_` as a SQL LIKE wildcard, which would let this marker also
    * match the GraphQL suite's marker (they run in parallel against the same account).
    */
  private val Marker = "ZZZAPITEST"

  // Minimal decoder for explore-mode rows. Some descriptions are null.
  final case class ExploreEntry(name: String, description: Option[String]) derives zio.json.JsonDecoder
