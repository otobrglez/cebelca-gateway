package biz.cebelca.gateway.graphql

import biz.cebelca.gateway
import biz.cebelca.gateway.{CebelcaError, CebelcaToken}
import zio.query.ZQuery

private[graphql] type PartnerID = Long

/** Arguments for the `partner(id: …)` query. A named args case class makes the GraphQL argument `id` (a bare
  * `Long => …` resolver would expose it as the anonymous `value` instead).
  */
final private[graphql] case class PartnerArgs(id: PartnerID)

/** Kind-of-partner filter, mirroring the cebelca UI's `contacts.html?f=…` tabs. The real filter lives only on the
  * session-authenticated UI backend (`partner select-all-safe&filter=…`), which this gateway does not use — so these
  * are **best-effort approximations** computed in memory over public-API data (partners + invoices):
  *
  *   - [[All]]      — every non-disabled partner (UI also hides the warehouse pseudo-partner; we can't detect that)
  *   - [[WithSent]] — partners that have at least one sent invoice
  *   - [[Debtors]]  — partners with at least one unpaid invoice (`payment != "paid"`)
  *   - [[Passive]]  — partners with no invoices at all
  *   - [[Disabled]] — partners flagged `disabled`
  *   - [[Last]]     — UI orders by recency only; we can't replicate that, so this behaves like [[All]]
  */
enum PartnerFilter:
  case All, WithSent, Debtors, Passive, Disabled, Last

/** Arguments for the `partners(ids:, filter:)` query. Both are optional and combine (AND): omit `ids` (or pass an
  * empty list) to match every id; omit `filter` to apply no kind-of filter. Upstream has no batch-by-id or usable
  * filter method on the public API, so the resolver fetches all partners in one `select-all` call (plus invoices when
  * the chosen filter needs them) and filters in memory.
  */
final private[graphql] case class PartnersArgs(ids: Option[List[PartnerID]], filter: Option[PartnerFilter])

/** A single line item on an invoice. `lines` on [[Invoice]] is a batched [[ZQuery]] field (see below), so selecting
  * `invoice.lines` across many invoices collapses into one upstream `invoice-sent-b select-all`.
  */
final private[graphql] case class Line(
  id: Long,
  title: String,
  qty: Double,
  mu: String,
  price: Double,
  vat: Double,
  discount: Double
)
private[graphql] object Line:
  def from(l: gateway.InvoiceLine): Line = Line(l.id, l.title, l.qty, l.mu, l.price, l.vat, l.discount)

final private[graphql] case class Invoice(
  id: Long,
  title: String,
  dateSent: String,
  payment: String,
  lines: ZQuery[CebelcaToken, CebelcaError, List[Line]]
)
private[graphql] object Invoice:
  def from(i: gateway.InvoiceHead)(lines: Long => ZQuery[CebelcaToken, CebelcaError, List[Line]]): Invoice =
    Invoice(i.id, i.title, i.date_sent, i.payment, lines(i.id))

/** `invoices` is a [[ZQuery]] field, not a plain value: when a query selects `partner.invoices` across many partners,
  * caliban merges every such request into one ZQuery, and the DataSource (see GraphQLAPI) batches them into a single
  * upstream `select-all` — avoiding the N+1 that a naive per-partner call would cause. Schema is derived in GraphQLAPI
  * where the CebelcaToken env (carried by this field) is in scope.
  */
final private[graphql] case class Partner(
  id: Long,
  name: String,
  city: String,
  invoices: ZQuery[CebelcaToken, CebelcaError, List[Invoice]]
)
