package biz.cebelca.gateway

import zio.*
import zio.http.*
import zio.json.JsonDecoder

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

  def query[A: JsonDecoder](cmd: Cmd): APITask[List[A]] =
    CebelcaToken.mapZIO(t => authed(Mw.basicAuth(t.value)).mapZIO(Envelope.rows[A](_)).run(cmd))

  def queryFirst[A: JsonDecoder](cmd: Cmd): APITask[A] =
    CebelcaToken.mapZIO(t => authed(Mw.basicAuth(t.value)).mapZIO(Envelope.first[A](_)).run(cmd))

  def ack(cmd: Cmd): APITask[Boolean] =
    CebelcaToken.mapZIO(t => authed(Mw.basicAuth(t.value)).mapZIO(Envelope.ack).run(cmd))

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
  private def serviceFields(
    title: String,
    price: Double,
    mu: String,
    vat: Double,
    group: String,
    konto: String
  ): Seq[(String, String)] =
    Seq(
      "object_title" -> title,
      "price"        -> price.toString,
      "measure_unit" -> mu,
      "vat"          -> vat.toString,
      "group_"       -> group,
      "konto"        -> konto
    )

  /** Create a service. `insert-into` returns only the new id, so we re-read the full row to return it. */
  def createService(
    title: String,
    price: Double,
    mu: String,
    vat: Double,
    group: String,
    konto: String
  ): APITask[Service] =
    queryFirst[IdRow](Cmd.insert("invoice-sent-o", serviceFields(title, price, mu, vat, group, konto)*))
      .flatMap(row => service(row.id))

  /** Update a service (full replace). `update-select` echoes the updated row, so no re-read is needed. */
  def updateService(
    id: Long,
    title: String,
    price: Double,
    mu: String,
    vat: Double,
    group: String,
    konto: String
  ): APITask[Service] =
    queryFirst[Service](Cmd.update("invoice-sent-o", id, serviceFields(title, price, mu, vat, group, konto)*))

  def deleteService(id: Long): APITask[Boolean] = ack(Cmd.delete("invoice-sent-o", id))

object CebelcaAPI:
  private type APITask[+A] = ZIO[CebelcaAPI & CebelcaToken, CebelcaError, A]
  private val baseUrl = URL.decode("https://www.cebelca.biz/API").toOption.get

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

  def createService(
    title: String,
    price: Double,
    mu: String,
    vat: Double,
    group: String,
    konto: String
  ): APITask[Service] = mapZIO(_.createService(title, price, mu, vat, group, konto))

  def updateService(
    id: Long,
    title: String,
    price: Double,
    mu: String,
    vat: Double,
    group: String,
    konto: String
  ): APITask[Service] =
    mapZIO(_.updateService(id, title, price, mu, vat, group, konto))

  def deleteService(id: Long): APITask[Boolean] = mapZIO(_.deleteService(id))
