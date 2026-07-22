package biz.cebelca.gateway.graphql

import biz.cebelca.gateway.{CebelcaAPI, CebelcaError, CebelcaToken}
import biz.cebelca.gateway.graphql
import zio.*
import zio.query.{DataSource, Request, ZQuery}
import caliban.*
import caliban.schema.Schema
import caliban.schema.ArgBuilder.auto.*

final case class Queries(
  partners: PartnersArgs => RIO[CebelcaToken, List[graphql.Partner]],
  partner: PartnerArgs => RIO[CebelcaToken, graphql.Partner],
  services: RIO[CebelcaToken, List[graphql.Service]],
  invoices: InvoicesArgs => RIO[CebelcaToken, List[graphql.Invoice]]
)

object GraphQLAPI:
  private val schema = new caliban.schema.GenericSchema[CebelcaToken] {}
  import schema.given

  private given Schema[Any, graphql.PartnerFilter]    = Schema.gen
  private given Schema[Any, graphql.PartnerArgs]      = Schema.gen
  private given Schema[Any, graphql.PartnersArgs]     = Schema.gen
  private given Schema[Any, graphql.InvoicesArgs]     = Schema.gen
  private given Schema[Any, graphql.Line]             = Schema.gen
  private given Schema[Any, graphql.Service]          = Schema.gen
  private given Schema[CebelcaToken, graphql.Invoice] = Schema.gen
  private given Schema[CebelcaToken, graphql.Partner] = Schema.gen
  private given Schema[CebelcaToken, graphql.Queries] = Schema.gen

  private def sanitizeError(e: CebelcaError): Throwable = new RuntimeException(e.getMessage)

  /** Keep-all when no ids are requested (`None`/empty), otherwise keep only partners whose id is in the set. */
  private def keepById(ids: Option[List[Long]]): biz.cebelca.gateway.Partner => Boolean =
    ids.filter(_.nonEmpty) match
      case None      => _ => true
      case Some(set) => val wanted = set.toSet; p => wanted.contains(p.id)

  /** Resolve the `partners` list. The kind-of filter and name `search` are delegated to the server via
    * `partner select-all-safe&filter=…&search=…` (the same authoritative call the UI's tabs + search box make — no
    * in-memory approximation). `ids`, which has no upstream batch method, is intersected in memory over the rows.
    */
  private def selectPartners(api: CebelcaAPI)(args: PartnersArgs): RIO[CebelcaToken, List[graphql.Partner]] =
    val filter = args.filter.getOrElse(graphql.PartnerFilter.All).wire
    api
      .partnersFiltered(filter, args.search, args.page.getOrElse(-1))
      .map(_.filter(keepById(args.ids)).map(toPartner(api)))
      .mapError(sanitizeError)

  private case class InvoicesForPartner(partnerId: Long, dateFrom: Option[String], dateTo: Option[String])
      extends Request[CebelcaError, List[graphql.Invoice]]
  private case class LinesForInvoice(invoiceId: Long) extends Request[CebelcaError, List[graphql.Line]]

  /** Batches `partner.invoices` fetches. Requests are grouped by their date window (`dateFrom`/`dateTo`): each distinct
    * window costs one upstream `select-all-by` call, whose rows are then partitioned by partner. So `{ partners {
    * invoices } }` (all one window) stays a single call, while different windows on different partners each get their own.
    */
  private def invoicesDataSource(api: CebelcaAPI): DataSource[CebelcaToken, InvoicesForPartner] =
    DataSource.fromFunctionBatchedZIO[CebelcaToken, CebelcaError, InvoicesForPartner, List[graphql.Invoice]](
      "invoices-by-partner"
    ) { reqs =>
      ZIO
        .foreachPar(reqs.groupBy(r => (r.dateFrom, r.dateTo)).toList) { case ((from, to), group) =>
          api.invoicesBetween(from, to).map(rows => (group, rows.groupBy(_.id_partner)))
        }
        .map { grouped =>
          // reassemble results in the original request order
          val byRequest = grouped.flatMap { (group, byPartner) =>
            group.map(r => r -> byPartner.getOrElse(r.partnerId, Nil).map(graphql.Invoice.from(_)(linesOf(api))))
          }.toMap
          reqs.map(byRequest)
        }
    }

  private def linesDataSource(api: CebelcaAPI): DataSource[CebelcaToken, LinesForInvoice] =
    DataSource.fromFunctionBatchedZIO[CebelcaToken, CebelcaError, LinesForInvoice, List[graphql.Line]](
      "lines-by-invoice"
    ) { reqs =>
      api.invoiceLines.map { all =>
        val byInvoice = all.groupBy(_.id_invoice_sent)
        reqs.map(r => byInvoice.getOrElse(r.invoiceId, Nil).map(graphql.Line.from))
      }
    }

  private def linesOf(api: CebelcaAPI)(invoiceId: Long): ZQuery[CebelcaToken, CebelcaError, List[graphql.Line]] =
    ZQuery.fromRequest(LinesForInvoice(invoiceId))(linesDataSource(api))

  private def toPartner(api: CebelcaAPI)(p: biz.cebelca.gateway.Partner): graphql.Partner =
    graphql.Partner(
      id = p.id,
      name = p.name,
      city = p.city,
      invoices = args => ZQuery.fromRequest(InvoicesForPartner(p.id, args.dateFrom, args.dateTo))(invoicesDataSource(api))
    )

  def make(api: CebelcaAPI): GraphQL[CebelcaToken] =
    val resolver = RootResolver(
      Queries(
        partners = selectPartners(api),
        partner = args => api.partner(args.id).mapBoth(sanitizeError, toPartner(api)),
        services = api.services.mapBoth(sanitizeError, _.map(graphql.Service.from)),
        invoices = args =>
          api
            .invoicesBetween(args.dateFrom, args.dateTo)
            .mapBoth(sanitizeError, _.map(graphql.Invoice.from(_)(linesOf(api))))
      )
    )
    graphQL(resolver)
