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
  partner: PartnerArgs => RIO[CebelcaToken, graphql.Partner]
)

object GraphQLAPI:
  private val schema = new caliban.schema.GenericSchema[CebelcaToken] {}
  import schema.given

  private given Schema[Any, graphql.PartnerFilter]    = Schema.gen
  private given Schema[Any, graphql.PartnerArgs]      = Schema.gen
  private given Schema[Any, graphql.PartnersArgs]     = Schema.gen
  private given Schema[Any, graphql.Line]             = Schema.gen
  private given Schema[CebelcaToken, graphql.Invoice] = Schema.gen
  private given Schema[CebelcaToken, graphql.Partner] = Schema.gen
  private given Schema[CebelcaToken, graphql.Queries] = Schema.gen

  private def sanitizeError(e: CebelcaError): Throwable = new RuntimeException(e.getMessage)

  /** Keep-all when no ids are requested (`None`/empty), otherwise keep only partners whose id is in the set. */
  private def keepById(ids: Option[List[Long]]): biz.cebelca.gateway.Partner => Boolean =
    ids.filter(_.nonEmpty) match
      case None      => _ => true
      case Some(set) => val wanted = set.toSet; p => wanted.contains(p.id)

  /** True when the filter is one whose predicate depends on invoice data (and so needs the extra upstream fetch). */
  private def needsInvoices(f: graphql.PartnerFilter): Boolean =
    f match
      case graphql.PartnerFilter.WithSent | graphql.PartnerFilter.Debtors | graphql.PartnerFilter.Passive => true
      case _                                                                                              => false

  /** Build the kind-of-partner predicate. See [[graphql.PartnerFilter]] for the (approximate) semantics. Invoice-based
    * filters receive the partner-id sets precomputed from `invoices`; id-only filters ignore them.
    */
  private def keepByFilter(
    filter: Option[graphql.PartnerFilter],
    withInvoices: Set[Long],
    debtors: Set[Long]
  ): biz.cebelca.gateway.Partner => Boolean =
    import graphql.PartnerFilter.*
    filter match
      case None | Some(All) | Some(Last) => p => p.disabled == 0
      case Some(Disabled)                => p => p.disabled != 0
      case Some(WithSent)                => p => withInvoices.contains(p.id)
      case Some(Debtors)                 => p => debtors.contains(p.id)
      case Some(Passive)                 => p => p.disabled == 0 && !withInvoices.contains(p.id)

  /** Resolve the `partners` list: one `select-all` for partners, plus (only when the chosen filter needs it) one
    * `invoice-sent select-all` to derive the with-invoices / debtor id sets. Both id and kind filters apply in memory.
    */
  private def selectPartners(api: CebelcaAPI)(args: PartnersArgs): RIO[CebelcaToken, List[graphql.Partner]] =
    val invoiceSets: ZIO[CebelcaToken, CebelcaError, (Set[Long], Set[Long])] =
      if args.filter.exists(needsInvoices) then
        api.invoices.map { invs =>
          val withInvoices = invs.map(_.id_partner).toSet
          val debtors      = invs.filter(_.payment != "paid").map(_.id_partner).toSet
          (withInvoices, debtors)
        }
      else ZIO.succeed((Set.empty, Set.empty))

    (for
      partners                 <- api.partners
      (withInvoices, debtors) <- invoiceSets
    yield partners
      .filter(keepById(args.ids))
      .filter(keepByFilter(args.filter, withInvoices, debtors))
      .map(toPartner(api))).mapError(sanitizeError)

  private case class InvoicesForPartner(partnerId: Long) extends Request[CebelcaError, List[graphql.Invoice]]
  private case class LinesForInvoice(invoiceId: Long)    extends Request[CebelcaError, List[graphql.Line]]

  private def invoicesDataSource(api: CebelcaAPI): DataSource[CebelcaToken, InvoicesForPartner] =
    DataSource.fromFunctionBatchedZIO[CebelcaToken, CebelcaError, InvoicesForPartner, List[graphql.Invoice]](
      "invoices-by-partner"
    ) { reqs =>
      api.invoices.map { all =>
        val byPartner = all.groupBy(_.id_partner)
        reqs.map(r => byPartner.getOrElse(r.partnerId, Nil).map(graphql.Invoice.from(_)(linesOf(api))))
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
      invoices = ZQuery.fromRequest(InvoicesForPartner(p.id))(invoicesDataSource(api))
    )

  def make(api: CebelcaAPI): GraphQL[CebelcaToken] =
    val resolver = RootResolver(
      Queries(
        partners = selectPartners(api),
        partner = args => api.partner(args.id).mapBoth(sanitizeError, toPartner(api))
      )
    )
    graphQL(resolver)
