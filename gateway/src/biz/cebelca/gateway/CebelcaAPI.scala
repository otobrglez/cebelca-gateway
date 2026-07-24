package biz.cebelca.gateway

import zio.*
import zio.http.*
import zio.json.JsonDecoder
import zio.metrics.*

final case class CebelcaAPI private (
  private val client: Client,
  private val backend: Backend
):
  private type APITask[+A] = ZIO[CebelcaToken, CebelcaError, A]

  private val transport: Call[Request, Response] = Call { req =>
    val label = s"${req.method} ${req.url.encode}"
    (for
      (duration, response) <- client.batched(req).mapError(CebelcaError.Transport(_)).timed
      _                    <- ZIO.logInfo(s"cebelca <- $label ${response.status.code} ${duration.toMillis}ms")
    yield response).tapError(e => ZIO.logWarning(s"cebelca <- $label failed: ${e.getMessage}"))
  }

  private val transportSilent: Call[Request, Response] =
    Call(req => client.batched(req).mapError(CebelcaError.Transport(_)))

  /** Auth (contravariant input) is resolved per call from the request-scoped [[CebelcaToken]]. */
  private def authed(mw: Mw): Call[Cmd, Response] =
    transportSilent.contramapZIO(mw).contramap(backend.toRequest).log

  /** Record one upstream cebelca.biz call into ZIO's metric registry (scraped at :METRICS_PORT/metrics):
    *   - `cebelca_api_requests_total` — counter, labels: resource, method, status (ok/error)
    *   - `cebelca_api_request_duration_seconds` — histogram, labels: resource, method
    *
    * Keyed by the [[Cmd]]'s `resource`/`method` (a small, fixed set) so label cardinality stays bounded — the
    * per-row args are deliberately excluded. Timed over the whole call incl. envelope decoding.
    */
  private def metered[A](cmd: Cmd)(io: APITask[A]): APITask[A] =
    val tags       = Set(MetricLabel("resource", cmd.resource), MetricLabel("method", cmd.method))
    def count(status: String) = Metric.counter("cebelca_api_requests_total").tagged(tags + MetricLabel("status", status))
    val duration              = Metric.histogram("cebelca_api_request_duration_seconds", CebelcaAPI.apiBuckets).tagged(tags)
    io.exit.timed.flatMap { (elapsed, exit) =>
      val status = if exit.isSuccess then "ok" else "error"
      count(status).increment *> duration.update(elapsed.toNanos.toDouble / 1e9) *> ZIO.suspendSucceed(exit)
    }

  def query[A: JsonDecoder](cmd: Cmd): APITask[List[A]] =
    metered(cmd)(CebelcaToken.mapZIO(t => authed(Mw.basicAuth(t.value)).mapZIO(Envelope.rows[A](_)).run(cmd)))

  def queryFirst[A: JsonDecoder](cmd: Cmd): APITask[A] =
    metered(cmd)(CebelcaToken.mapZIO(t => authed(Mw.basicAuth(t.value)).mapZIO(Envelope.first[A](_)).run(cmd)))

  def ack(cmd: Cmd): APITask[Boolean] =
    metered(cmd)(CebelcaToken.mapZIO(t => authed(Mw.basicAuth(t.value)).mapZIO(Envelope.ack).run(cmd)))

  // ── domain surface (grows as needed) ──
  def partners: APITask[List[Partner]] = query[Partner](Cmd.select("partner"))

  def partnersFiltered(filter: String, search: Option[String] = None, page: Int = -1): APITask[List[Partner]] =
    query[Partner](Cmd.selectAllSafe("partner", filter, search, page))

  def partner(id: Long): APITask[Partner] = queryFirst[Partner](Cmd.selectOne("partner", id))

  def invoices: APITask[List[InvoiceHead]] = query[InvoiceHead](Cmd.select("invoice-sent"))

  def invoicesBy(
    filter: String = "all",
    dateFrom: Option[String] = None,
    dateTo: Option[String] = None
  ): APITask[List[InvoiceHead]] =
    query[InvoiceHead](Cmd.selectAllBy("invoice-sent", filter = filter, dateFrom = dateFrom, dateTo = dateTo))

  def invoiceLines: APITask[List[InvoiceLine]] = query[InvoiceLine](Cmd.select("invoice-sent-b"))

  def service(id: Long): APITask[Service] = queryFirst[Service](Cmd.selectOne("invoice-sent-o", id))

  def services(search: Option[String] = None): APITask[List[Service]] = search.filterNot(_.isEmpty) match
    case search @ Some(_) => query[Service](Cmd.selectAllBy("invoice-sent-o", search = search))
    case None             => query[Service](Cmd.select("invoice-sent-o"))

  /** The upstream wire fields for a service row (`invoice-sent-o`). Field names/format mirror what `select-all`
    * returns: `object_title`, `price`, `measure_unit`, `vat`, `group_`, `konto`.
    */
  private def serviceWire(f: ServiceFields): Seq[(String, String)] =
    Seq(
      "object_title" -> f.title,
      "price"        -> f.price.toString,
      "measure_unit" -> f.mu,
      "vat"          -> f.vat.toString,
      "group_"       -> f.group,
      "konto"        -> f.konto
    )

  /** Create a service. `insert-into` returns only the new id, so we re-read the full row to return it. */
  def createService(f: ServiceFields): APITask[Service] =
    queryFirst[IdRow](Cmd.insert("invoice-sent-o", serviceWire(f)*)).flatMap(row => service(row.id))

  /** Update a service (full replace). `update-select` echoes the updated row, so no re-read is needed. */
  def updateService(id: Long, f: ServiceFields): APITask[Service] =
    queryFirst[Service](Cmd.update("invoice-sent-o", id, serviceWire(f)*))

  def deleteService(id: Long): APITask[Boolean] = ack(Cmd.delete("invoice-sent-o", id))

  /** The upstream wire fields for a partner row. Names match what `select-one`/`select-all` return. */
  private def partnerWire(f: PartnerFields): Seq[(String, String)] =
    Seq(
      "name"    -> f.name,
      "street"  -> f.street,
      "postal"  -> f.postal,
      "city"    -> f.city,
      "vatid"   -> f.vatid,
      "country" -> f.country,
      "lang"    -> f.lang
    )

  /** Create a partner. `insert-into` returns only the new id, so we re-read the full row to return it. */
  def createPartner(f: PartnerFields): APITask[Partner] =
    queryFirst[IdRow](Cmd.insert("partner", partnerWire(f)*)).flatMap(row => partner(row.id))

  /** Update a partner (full replace). `update-select` echoes the updated row, so no re-read is needed. */
  def updatePartner(id: Long, f: PartnerFields): APITask[Partner] =
    queryFirst[Partner](Cmd.update("partner", id, partnerWire(f)*))

  def deletePartner(id: Long): APITask[Boolean] = ack(Cmd.delete("partner", id))

  def payment(id: Long): APITask[Payment] = queryFirst[Payment](Cmd.selectOne("invoice-sent-p", id))

  /** The upstream wire fields for a payment row (`invoice-sent-p`). `date_of` is sent as ISO `YYYY-MM-DD` — unlike
    * invoices, the payment endpoint's `assure-iso-date` coercion accepts ISO directly, so no ISO→SI conversion.
    */
  private def paymentWire(f: PaymentFields): Seq[(String, String)] =
    Seq(
      "id_invoice_sent"   -> f.invoiceId.toString,
      "date_of"           -> f.dateOf,
      "amount"            -> f.amount.toString,
      "id_payment_method" -> f.paymentMethod.toString,
      "note"              -> f.note
    )

  /** Record a payment against an invoice (`invoice-sent-p insert-into`). `insert-into` returns only the new id, so we
    * re-read the full row to return it.
    */
  def recordPayment(f: PaymentFields): APITask[Payment] =
    queryFirst[IdRow](Cmd.insert("invoice-sent-p", paymentWire(f)*)).flatMap(row => payment(row.id))

  /** Update a payment (full replace). `update-select` echoes the updated row, so no re-read is needed. */
  def updatePayment(id: Long, f: PaymentFields): APITask[Payment] =
    queryFirst[Payment](Cmd.update("invoice-sent-p", id, paymentWire(f)*))

  def deletePayment(id: Long): APITask[Boolean] = ack(Cmd.delete("invoice-sent-p", id))

  def invoice(id: Long): APITask[InvoiceHead] = queryFirst[InvoiceHead](Cmd.selectOne("invoice-sent", id))

  /** Invoice head wire fields. Dates are converted ISO→SI (`DD.MM.YYYY`): the upstream insert silently stores an empty
    * date if given ISO. `date_served` defaults to `date_sent` when the caller omits it.
    */
  private def invoiceWire(f: InvoiceFields): Seq[(String, String)] =
    Seq(
      "date_sent"   -> Dates.isoToSi(f.dateSent),
      "date_to_pay" -> Dates.isoToSi(f.dateToPay),
      "date_served" -> Dates.isoToSi(f.dateServed.getOrElse(f.dateSent)),
      "id_partner"  -> f.partnerId.toString
    )

  private def lineWire(invoiceId: Long, l: LineFields): Seq[(String, String)] =
    Seq(
      "title"           -> l.title,
      "qty"             -> l.qty.toString,
      "mu"              -> l.mu,
      "price"           -> l.price.toString,
      "vat"             -> l.vat.toString,
      "discount"        -> l.discount.toString,
      "id_invoice_sent" -> invoiceId.toString
    )

  def createInvoiceHead(f: InvoiceFields): APITask[Long] =
    queryFirst[IdRow](Cmd.insert("invoice-sent", invoiceWire(f)*)).map(_.id)

  def updateInvoiceHead(id: Long, f: InvoiceFields): APITask[InvoiceHead] =
    queryFirst[InvoiceHead](Cmd.update("invoice-sent", id, invoiceWire(f)*))

  /** Delete an invoice. Upstream `invoice-sent delete` returns a spurious HTTP 500 even when the row IS deleted, so we
    * can't trust the ack — instead we ignore the delete's result and verify by re-reading: gone (empty) ⇒ success.
    */
  def deleteInvoice(id: Long): APITask[Boolean] =
    ack(Cmd.delete("invoice-sent", id)).ignore *>
      query[InvoiceHead](Cmd.selectOne("invoice-sent", id)).map(_.isEmpty)

  def addLine(invoiceId: Long, l: LineFields): APITask[Long] =
    queryFirst[IdRow](Cmd.insert("invoice-sent-b", lineWire(invoiceId, l)*)).map(_.id)

  def updateLine(lineId: Long, invoiceId: Long, l: LineFields): APITask[InvoiceLine] =
    queryFirst[InvoiceLine](Cmd.update("invoice-sent-b", lineId, lineWire(invoiceId, l)*))

  def deleteLine(lineId: Long): APITask[Boolean] = ack(Cmd.delete("invoice-sent-b", lineId))

  /** Create a draft invoice: insert the head, then each line in order, and return the (re-read) head. */
  def createInvoice(f: InvoiceFields, lines: List[LineFields]): APITask[InvoiceHead] =
    for
      id <- createInvoiceHead(f)
      _  <- ZIO.foreachDiscard(lines)(addLine(id, _))
      hd <- invoice(id)
    yield hd

  /** The recommended next invoice number for a year (`select-next-title`), e.g. `26-0004`. `doctype` selects the
    * numbering series (default 0). This is what the UI pre-fills into the finalize dialog's editable number field.
    */
  def proposedInvoiceTitle(year: Int, doctype: Int = 0): APITask[String] =
    queryFirst[ProposedTitle](
      Cmd.custom("invoice-sent", "select-next-title", "year" -> year.toString, "doctype" -> doctype.toString)
    ).map(_.proposed_title)

  /** Finalize a draft into a numbered document WITHOUT fiscal/FURS registration (`finalize-invoice-2015`). Assigns the
    * invoice number; we ignore the `new_title` ack and re-read the head so the caller gets the numbered invoice.
    * `doctype` selects the numbering series (default 0, matching existing invoices). `title` optionally overrides the
    * auto-assigned number (matching the UI's editable proposed-number field); omit it to let the server assign the
    * next number. A duplicate title fails upstream (`not-unique-value-title`).
    */
  def finalizeInvoice(id: Long, doctype: Int = 0, title: Option[String] = None): APITask[InvoiceHead] =
    val args = Seq("id" -> id.toString, "doctype" -> doctype.toString) ++ title.filter(_.nonEmpty).map("title" -> _)
    ack(Cmd.custom("invoice-sent", "finalize-invoice-2015", args*)) *> invoice(id)

  /** Duplicate an invoice into a new DRAFT (`duplicate-invoice`): copies the partner and line items, resets `date_sent`
    * to today (with `date_to_pay` recomputed) and clears the number — so it works as a "reissue" of any invoice, even a
    * finalized one. Returns the (re-read) new draft head.
    */
  def duplicateInvoice(id: Long): APITask[InvoiceHead] =
    queryFirst[RowId](Cmd.custom("invoice-sent", "duplicate-invoice", "id" -> id.toString))
      .flatMap(row => invoice(row.id))

object CebelcaAPI:
  private type APITask[+A] = ZIO[CebelcaAPI & CebelcaToken, CebelcaError, A]
  private val baseUrl = URL.decode("https://www.cebelca.biz/API").toOption.get

  /** Histogram buckets (seconds) for upstream latency — a wide upper range since cebelca.biz can be slow and
    * unpaged `select-all` reads over the network dominate. Mirrors the shape of Caliban's field-metric buckets.
    */
  private val apiBuckets: MetricKeyType.Histogram.Boundaries =
    MetricKeyType.Histogram.Boundaries.fromChunk(
      Chunk(0.01, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0)
    )

  def make: URIO[Client, CebelcaAPI]            = ZIO.serviceWith[Client](CebelcaAPI(_, Backend(baseUrl)))
  val live: ZLayer[Client, Nothing, CebelcaAPI] = ZLayer.fromZIO(make)

  private def mapZIO[A, R, B](action: CebelcaAPI => ZIO[A, R, B]): ZIO[A & CebelcaAPI, R, B] =
    ZIO.serviceWithZIO[CebelcaAPI](action)

  def partners: APITask[List[Partner]]     = mapZIO(_.partners)
  def invoices: APITask[List[InvoiceHead]] = mapZIO(_.invoices)

  def partnersFiltered(filter: String, search: Option[String] = None, page: Int = -1): APITask[List[Partner]] =
    mapZIO(_.partnersFiltered(filter, search, page))

  def invoicesBy(
    filter: String = "all",
    dateFrom: Option[String] = None,
    dateTo: Option[String] = None
  ): APITask[List[InvoiceHead]] =
    mapZIO(_.invoicesBy(filter, dateFrom, dateTo))

  def invoiceLines: APITask[List[InvoiceLine]]                        = mapZIO(_.invoiceLines)
  def services(search: Option[String] = None): APITask[List[Service]] = mapZIO(_.services(search))
  def service(id: Long): APITask[Service]                             = mapZIO(_.service(id))

  def createService(f: ServiceFields): APITask[Service]           = mapZIO(_.createService(f))
  def updateService(id: Long, f: ServiceFields): APITask[Service] = mapZIO(_.updateService(id, f))
  def deleteService(id: Long): APITask[Boolean]                   = mapZIO(_.deleteService(id))

  def partner(id: Long): APITask[Partner]                         = mapZIO(_.partner(id))
  def createPartner(f: PartnerFields): APITask[Partner]           = mapZIO(_.createPartner(f))
  def updatePartner(id: Long, f: PartnerFields): APITask[Partner] = mapZIO(_.updatePartner(id, f))
  def deletePartner(id: Long): APITask[Boolean]                   = mapZIO(_.deletePartner(id))

  def payment(id: Long): APITask[Payment]                        = mapZIO(_.payment(id))
  def recordPayment(f: PaymentFields): APITask[Payment]          = mapZIO(_.recordPayment(f))
  def updatePayment(id: Long, f: PaymentFields): APITask[Payment] = mapZIO(_.updatePayment(id, f))
  def deletePayment(id: Long): APITask[Boolean]                  = mapZIO(_.deletePayment(id))

  def invoice(id: Long): APITask[InvoiceHead]                              = mapZIO(_.invoice(id))
  def createInvoice(f: InvoiceFields, lines: List[LineFields]): APITask[InvoiceHead] =
    mapZIO(_.createInvoice(f, lines))
  def updateInvoiceHead(id: Long, f: InvoiceFields): APITask[InvoiceHead]  = mapZIO(_.updateInvoiceHead(id, f))
  def deleteInvoice(id: Long): APITask[Boolean]                           = mapZIO(_.deleteInvoice(id))
  def addLine(invoiceId: Long, l: LineFields): APITask[Long]              = mapZIO(_.addLine(invoiceId, l))
  def updateLine(lineId: Long, invoiceId: Long, l: LineFields): APITask[InvoiceLine] =
    mapZIO(_.updateLine(lineId, invoiceId, l))
  def deleteLine(lineId: Long): APITask[Boolean]                         = mapZIO(_.deleteLine(lineId))
  def proposedInvoiceTitle(year: Int, doctype: Int = 0): APITask[String] = mapZIO(_.proposedInvoiceTitle(year, doctype))
  def finalizeInvoice(id: Long, doctype: Int = 0, title: Option[String] = None): APITask[InvoiceHead] =
    mapZIO(_.finalizeInvoice(id, doctype, title))
  def duplicateInvoice(id: Long): APITask[InvoiceHead] = mapZIO(_.duplicateInvoice(id))
