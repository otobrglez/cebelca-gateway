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
      start <- Clock.nanoTime
      resp  <- client.batched(req).mapError(CebelcaError.Transport(_))
      end   <- Clock.nanoTime
      _     <- ZIO.logInfo(s"cebelca <- $label ${resp.status.code} ${(end - start) / 1000000}ms")
    yield resp).tapError(e => ZIO.logWarning(s"cebelca <- $label failed: ${e.getMessage}"))
  }

  /** Auth (contravariant input) is resolved per call from the request-scoped [[CebelcaToken]]. */
  private def authed(mw: Mw): Call[Cmd, Response] =
    transport.contramapZIO(mw).contramap(backend.toRequest)

  def query[A: JsonDecoder](cmd: Cmd): APITask[List[A]] =
    ZIO.serviceWithZIO[CebelcaToken](t => authed(Mw.basicAuth(t.value)).mapZIO(Envelope.rows[A](_)).run(cmd))

  def queryFirst[A: JsonDecoder](cmd: Cmd): APITask[A] =
    ZIO.serviceWithZIO[CebelcaToken](t => authed(Mw.basicAuth(t.value)).mapZIO(Envelope.first[A](_)).run(cmd))

  // ── domain surface (grows as needed) ──
  def partners: APITask[List[Partner]]    = query[Partner](Cmd.select("partner"))
  def partnersFiltered(filter: String, search: Option[String] = None, page: Int = -1): APITask[List[Partner]] =
    query[Partner](Cmd.selectAllSafe("partner", filter, search, page))
  def partner(id: Long): APITask[Partner] = queryFirst[Partner](Cmd.selectOne("partner", id))
  def invoices: APITask[List[InvoiceHead]]                     = query[InvoiceHead](Cmd.select("invoice-sent"))
  def invoicesBetween(dateFrom: Option[String], dateTo: Option[String]): APITask[List[InvoiceHead]] =
    query[InvoiceHead](Cmd.selectAllBy("invoice-sent", dateFrom = dateFrom, dateTo = dateTo))
  def invoiceLines: APITask[List[InvoiceLine]]                 = query[InvoiceLine](Cmd.select("invoice-sent-b"))
  def services: APITask[List[Service]]                         = query[Service](Cmd.select("invoice-sent-o"))

object CebelcaAPI:
  private type APITask[+A] = ZIO[CebelcaAPI & CebelcaToken, CebelcaError, A]
  private val baseUrl = URL.decode("https://www.cebelca.biz/API").toOption.get

  def make: URIO[Client, CebelcaAPI]            = ZIO.serviceWith[Client](CebelcaAPI(_, Backend(baseUrl)))
  val live: ZLayer[Client, Nothing, CebelcaAPI] = ZLayer.fromZIO(make)

  def partners: APITask[List[Partner]] = ZIO.serviceWithZIO[CebelcaAPI](_.partners)
  def partnersFiltered(filter: String, search: Option[String] = None, page: Int = -1): APITask[List[Partner]] =
    ZIO.serviceWithZIO[CebelcaAPI](_.partnersFiltered(filter, search, page))
  def invoices: APITask[List[InvoiceHead]]                     = ZIO.serviceWithZIO[CebelcaAPI](_.invoices)
  def invoicesBetween(dateFrom: Option[String], dateTo: Option[String]): APITask[List[InvoiceHead]] =
    ZIO.serviceWithZIO[CebelcaAPI](_.invoicesBetween(dateFrom, dateTo))
  def invoiceLines: APITask[List[InvoiceLine]]                 = ZIO.serviceWithZIO[CebelcaAPI](_.invoiceLines)
  def services: APITask[List[Service]]                         = ZIO.serviceWithZIO[CebelcaAPI](_.services)
