package biz.cebelca.gateway.graphql

import biz.cebelca.gateway
import biz.cebelca.gateway.{CebelcaError, CebelcaToken}
import zio.query.ZQuery

private[graphql] type PartnerID = Long

/** Arguments for the `partner(id: …)` query. A named args case class makes the GraphQL argument `id` (a bare
  * `Long => …` resolver would expose it as the anonymous `value` instead).
  */
final private[graphql] case class PartnerArgs(id: PartnerID)

/** Kind-of-partner filter, mirroring the cebelca UI's `contacts.html?f=…` tabs. Each case maps to the exact `filter=`
  * value the webapp passes to `partner select-all-safe`, so the server does the (authoritative) selection — the same
  * call the UI's "Dolžniki" button makes. [[wire]] is that value; note the API's own spelling `pasive` (one 's').
  *
  *   - [[All]]      — every partner (`all`)
  *   - [[WithSent]] — partners that have at least one sent invoice (`wsent`)
  *   - [[Debtors]]  — partners the server considers in debt (`debtors`)
  *   - [[Passive]]  — passive partners (`pasive`)
  *   - [[Disabled]] — hidden/disabled partners (`disabled`)
  *   - [[Last]]     — recently used partners (`last`)
  */
enum PartnerFilter(val wire: String):
  case All      extends PartnerFilter("all")
  case WithSent extends PartnerFilter("wsent")
  case Debtors  extends PartnerFilter("debtors")
  case Passive  extends PartnerFilter("pasive")
  case Disabled extends PartnerFilter("disabled")
  case Last     extends PartnerFilter("last")

/** Arguments for the `partners(ids:, filter:, search:, page:)` query. All optional and combine (AND): omit `ids` (or
  * pass an empty list) to match every id; omit `filter` to apply no kind-of filter (defaults to `all` upstream);
  * `search` is a case-insensitive substring match on the partner name; `page` selects a page (default/omitted = `-1`,
  * i.e. all pages unpaged). Filter, search and page are all resolved server-side via
  * `partner select-all-safe&filter=…&search=…&page=…` (the same call the UI's tab + search box make); `ids`, which has
  * no upstream batch method, is intersected in memory over the returned rows.
  */
final private[graphql] case class PartnersArgs(
  ids: Option[List[PartnerID]],
  filter: Option[PartnerFilter],
  search: Option[String],
  page: Option[Int]
)

/** Arguments for the top-level `invoices(dateFrom:, dateTo:)` query. Both optional and bound `date_sent`: `dateFrom` is
  * the inclusive lower bound, `dateTo` the inclusive upper bound; omit either for an open end. Dates are **ISO
  * `YYYY-MM-DD`** (the format the upstream `select-all-by` expects). Resolved server-side.
  */
final private[graphql] case class InvoicesArgs(dateFrom: Option[String], dateTo: Option[String])

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
  invoices: InvoicesArgs => ZQuery[CebelcaToken, CebelcaError, List[Invoice]]
)

/** A service / pricelist entry, mirroring the UI's "Storitve" page. Sourced from the `invoice-sent-o` resource;
  * field names are normalised from the upstream row (`object_title` → `title`, `measure_unit` → `mu`).
  */
final private[graphql] case class Service(
  id: Long,
  title: String,
  price: Double,
  mu: String,
  vat: Double,
  group: String,
  konto: String
)
private[graphql] object Service:
  def from(s: gateway.Service): Service =
    Service(s.id, s.object_title, s.price, s.measure_unit, s.vat, s.group, s.konto)
