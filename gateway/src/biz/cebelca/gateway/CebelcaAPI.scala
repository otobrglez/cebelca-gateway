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
      count(status).increment *> duration.update(elapsed.toNanos.toDouble / 1e9) *> ZIO.done(exit)
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
