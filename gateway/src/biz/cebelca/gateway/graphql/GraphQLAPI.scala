package biz.cebelca.gateway.graphql

import biz.cebelca.gateway.{CebelcaAPI, CebelcaError, CebelcaToken}
import biz.cebelca.gateway.graphql
import zio.*
import zio.query.{DataSource, Request, ZQuery}
import caliban.*
import caliban.schema.Schema
import caliban.schema.ArgBuilder.auto.*
import caliban.wrappers.FieldMetrics

final case class Queries(
  partners: PartnersArgs => RIO[CebelcaToken, List[graphql.Partner]],
  partner: PartnerArgs => RIO[CebelcaToken, graphql.Partner],
  services: ServicesArgs => RIO[CebelcaToken, List[graphql.Service]],
  invoices: InvoicesArgs => RIO[CebelcaToken, List[graphql.Invoice]]
)

final private[graphql] case class CreateServiceArgs(input: ServiceInput)
final private[graphql] case class UpdateServiceArgs(id: Long, input: ServiceInput)
final private[graphql] case class DeleteServiceArgs(id: Long)
final private[graphql] case class CreatePartnerArgs(input: PartnerInput)
final private[graphql] case class UpdatePartnerArgs(id: Long, input: PartnerInput)
final private[graphql] case class DeletePartnerArgs(id: Long)

final case class Mutations(
  createService: CreateServiceArgs => RIO[CebelcaToken, graphql.Service],
  updateService: UpdateServiceArgs => RIO[CebelcaToken, graphql.Service],
  deleteService: DeleteServiceArgs => RIO[CebelcaToken, Boolean],
  createPartner: CreatePartnerArgs => RIO[CebelcaToken, graphql.Partner],
  updatePartner: UpdatePartnerArgs => RIO[CebelcaToken, graphql.Partner],
  deletePartner: DeletePartnerArgs => RIO[CebelcaToken, Boolean]
)

object GraphQLAPI:
  private val schema = new caliban.schema.GenericSchema[CebelcaToken] {}
  import schema.given

  private given Schema[Any, graphql.CreateServiceArgs]  = Schema.gen
  private given Schema[Any, graphql.UpdateServiceArgs]  = Schema.gen
  private given Schema[Any, graphql.DeleteServiceArgs]  = Schema.gen
  private given Schema[Any, graphql.ServiceInput]       = Schema.gen
  private given Schema[Any, graphql.Service]            = Schema.gen
  private given Schema[Any, graphql.ServicesArgs]       = Schema.gen
  private given Schema[Any, graphql.CreatePartnerArgs]  = Schema.gen
  private given Schema[Any, graphql.UpdatePartnerArgs]  = Schema.gen
  private given Schema[Any, graphql.DeletePartnerArgs]  = Schema.gen
  private given Schema[Any, graphql.PartnerInput]       = Schema.gen
  private given Schema[Any, graphql.PartnerArgs]        = Schema.gen
  private given Schema[Any, graphql.PartnerFilter]      = Schema.gen
  private given Schema[Any, graphql.PartnersArgs]       = Schema.gen
  private given Schema[Any, graphql.InvoiceFilter]      = Schema.gen
  private given Schema[Any, graphql.InvoicesArgs]       = Schema.gen
  private given Schema[Any, graphql.Line]               = Schema.gen
  private given Schema[CebelcaToken, graphql.Invoice]   = Schema.gen
  private given Schema[CebelcaToken, graphql.Mutations] = Schema.gen
  private given Schema[CebelcaToken, graphql.Partner]   = Schema.gen
  private given Schema[CebelcaToken, graphql.Queries]   = Schema.gen

  private def sanitizeError(e: CebelcaError): Throwable = new RuntimeException(e.getMessage)

  /** Adapt a typed API effect into what resolvers surface: sanitise the [[CebelcaError]] to a generic throwable, and
    * (optionally) map the success value to its GraphQL representation. Removes the `.mapBoth(sanitizeError, …)` /
    * `.mapError(sanitizeError)` boilerplate repeated on every resolver.
    */
  extension [A](z: ZIO[CebelcaToken, CebelcaError, A])
    private def resolved: RIO[CebelcaToken, A]              = z.mapError(sanitizeError)
    private def resolvedAs[B](f: A => B): RIO[CebelcaToken, B] = z.mapBoth(sanitizeError, f)

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
      .resolvedAs(_.filter(keepById(args.ids)).map(toPartner(api)))

  private case class InvoicesForPartner(
    partnerId: Long,
    filter: String,
    dateFrom: Option[String],
    dateTo: Option[String]
  ) extends Request[CebelcaError, List[graphql.Invoice]]
  private case class LinesForInvoice(invoiceId: Long) extends Request[CebelcaError, List[graphql.Line]]

  /** Batches `partner.invoices` fetches. Requests are grouped by their query window (`filter` + `dateFrom`/`dateTo`):
    * each distinct window costs one upstream `select-all-by` call, whose rows are then partitioned by partner. So
    * `{ partners { invoices } }` (all one window) stays a single call, while different windows each get their own.
    */
  private def invoicesDataSource(api: CebelcaAPI): DataSource[CebelcaToken, InvoicesForPartner] =
    DataSource.fromFunctionBatchedZIO[CebelcaToken, CebelcaError, InvoicesForPartner, List[graphql.Invoice]](
      "invoices-by-partner"
    ) { reqs =>
      ZIO
        .foreachPar(reqs.groupBy(r => (r.filter, r.dateFrom, r.dateTo)).toList) { case ((filter, from, to), group) =>
          api.invoicesBy(filter, from, to).map(rows => (group, rows.groupBy(_.id_partner)))
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
      street = p.street,
      postal = p.postal,
      city = p.city,
      vatid = p.vatid,
      country = p.country,
      lang = p.lang,
      invoices = args =>
        ZQuery.fromRequest(
          InvoicesForPartner(p.id, args.filter.getOrElse(graphql.InvoiceFilter.All).wire, args.dateFrom, args.dateTo)
        )(invoicesDataSource(api))
    )

  def make(api: CebelcaAPI): GraphQL[CebelcaToken] =
    val resolver = RootResolver(
      Queries(
        partners = selectPartners(api),
        partner = args => api.partner(args.id).resolvedAs(toPartner(api)),
        services = args => api.services(args.search).resolvedAs(_.map(graphql.Service.from)),
        invoices = args =>
          api
            .invoicesBy(args.filter.getOrElse(graphql.InvoiceFilter.All).wire, args.dateFrom, args.dateTo)
            .resolvedAs(_.map(graphql.Invoice.from(_)(linesOf(api))))
      ),
      Mutations(
        createService = args => api.createService(graphql.ServiceInput.toFields(args.input)).resolvedAs(graphql.Service.from),
        updateService = args => api.updateService(args.id, graphql.ServiceInput.toFields(args.input)).resolvedAs(graphql.Service.from),
        deleteService = args => api.deleteService(args.id).resolved,
        createPartner = args => api.createPartner(graphql.PartnerInput.toFields(args.input)).resolvedAs(toPartner(api)),
        updatePartner = args => api.updatePartner(args.id, graphql.PartnerInput.toFields(args.input)).resolvedAs(toPartner(api)),
        deletePartner = args => api.deletePartner(args.id).resolved
      )
    )
    // FieldMetrics emits per-field `graphql_fields_total` (labels: field, status) and
    // `graphql_fields_duration_seconds` (label: field) into ZIO's metric registry — scraped at :METRICS_PORT/metrics.
    graphQL(resolver) @@ FieldMetrics.wrapper()
