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
  *   - [[All]] — every partner (`all`)
  *   - [[WithSent]] — partners that have at least one sent invoice (`wsent`)
  *   - [[Debtors]] — partners the server considers in debt (`debtors`)
  *   - [[Passive]] — passive partners (`pasive`)
  *   - [[Disabled]] — hidden/disabled partners (`disabled`)
  *   - [[Last]] — recently used partners (`last`)
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

/** Status filter for invoices, mirroring the cebelca UI's `invoices.html?f=…` tabs. Each case maps to the exact
  * `filter=` value the webapp passes to `invoice-sent select-all-by`, so the server does the (authoritative) selection.
  * [[wire]] is that value; note the API's own spelling `payed`/`unpayed`.
  *
  *   - [[All]] — every invoice (`all`)
  *   - [[Paid]] — paid invoices (`payed`)
  *   - [[Unpaid]] — unpaid invoices (`unpayed`)
  *   - [[PastDue]] — overdue invoices (`pastdue`)
  *   - [[Archived]] — archived invoices (`archived`)
  */
enum InvoiceFilter(val wire: String):
  case All      extends InvoiceFilter("all")
  case Paid     extends InvoiceFilter("payed")
  case Unpaid   extends InvoiceFilter("unpayed")
  case PastDue  extends InvoiceFilter("pastdue")
  case Archived extends InvoiceFilter("archived")

/** The kind of document a draft becomes when finalized, selecting both its semantics and its own numbering series. Each
  * case maps to the upstream integer `doctype` (per the cebelca UI: `0 inv, 1 avans, 2 credit note, 3 storno, 10 final`).
  *
  *   - [[Invoice]]      — a regular invoice (`0`, the default)
  *   - [[Advance]]      — an advance/prepayment invoice (avansni račun, `1`)
  *   - [[CreditNote]]   — a credit note (dobropis, `2`)
  *   - [[Storno]]       — a cancellation/reversal document (`3`)
  *   - [[FinalInvoice]] — a final invoice settling a prior advance (končni račun, `10`)
  */
enum DocumentType(val wire: Int):
  case Invoice      extends DocumentType(0)
  case Advance      extends DocumentType(1)
  case CreditNote   extends DocumentType(2)
  case Storno       extends DocumentType(3)
  case FinalInvoice extends DocumentType(10)

/** Arguments for the `invoices(filter:, dateFrom:, dateTo:)` query and the nested `partner.invoices(…)` field. All
  * optional: `filter` is a status tab (defaults to `all` upstream); `dateFrom`/`dateTo` bound `date_sent` (inclusive,
  * open-ended if omitted). Dates are **ISO `YYYY-MM-DD`** (the format the upstream `select-all-by` expects). All are
  * resolved server-side.
  */
final private[graphql] case class InvoicesArgs(
  filter: Option[InvoiceFilter],
  dateFrom: Option[String],
  dateTo: Option[String]
)

final private[graphql] case class ServicesArgs(
  search: Option[String]
)

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

/** `payment` is the payment *terms/method* (upstream `payment`), NOT the paid status — do not use it to tell whether an
  * invoice is settled. `paid` / `datePaid` (derived from upstream `date_payed`) are the authoritative paid signals:
  * `paid` is true iff a payment date exists, and `datePaid` is that date.
  */
final private[graphql] case class Invoice(
  id: Long,
  title: String,
  dateSent: String,
  payment: String,
  paid: Boolean,
  datePaid: Option[String],
  lines: ZQuery[CebelcaToken, CebelcaError, List[Line]]
)
private[graphql] object Invoice:
  def from(i: gateway.InvoiceHead)(lines: Long => ZQuery[CebelcaToken, CebelcaError, List[Line]]): Invoice =
    val datePaid = gateway.PaidDate.value(i.date_payed)
    Invoice(i.id, i.title, i.date_sent, i.payment, datePaid.isDefined, datePaid, lines(i.id))

/** `invoices` is a [[ZQuery]] field, not a plain value: when a query selects `partner.invoices` across many partners,
  * caliban merges every such request into one ZQuery, and the DataSource (see GraphQLAPI) batches them into a single
  * upstream `select-all` — avoiding the N+1 that a naive per-partner call would cause. Schema is derived in GraphQLAPI
  * where the CebelcaToken env (carried by this field) is in scope.
  */
final private[graphql] case class Partner(
  id: Long,
  name: String,
  street: String,
  postal: String,
  city: String,
  vatid: String,
  country: String,
  lang: String,
  invoices: InvoicesArgs => ZQuery[CebelcaToken, CebelcaError, List[Invoice]]
)

/** Input for creating/updating a [[Partner]]. Only `name` is required upstream; the rest default to empty. Update is a
  * full replace — supply every field you want to keep, not just the changed ones.
  */
final private[graphql] case class PartnerInput(
  name: String,
  street: Option[String],
  postal: Option[String],
  city: Option[String],
  vatid: Option[String],
  country: Option[String],
  lang: Option[String]
)
private[graphql] object PartnerInput:
  def toFields(i: PartnerInput): gateway.PartnerFields =
    gateway.PartnerFields(
      name = i.name,
      street = i.street.getOrElse(""),
      postal = i.postal.getOrElse(""),
      city = i.city.getOrElse(""),
      vatid = i.vatid.getOrElse(""),
      country = i.country.getOrElse(""),
      lang = i.lang.getOrElse("")
    )

/** A service / pricelist entry, mirroring the UI's "Storitve" page. Sourced from the `invoice-sent-o` resource; field
  * names are normalised from the upstream row (`object_title` → `title`, `measure_unit` → `mu`).
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

/** Input for creating/updating a [[Service]]. Field names mirror the [[Service]] output type (`title`, `mu`); the API
  * layer maps them to the upstream `object_title`/`measure_unit`. `group`/`konto` are optional (default `""`). Update
  * is a full replace — every non-optional field must be supplied on update, not just the changed ones.
  */
final private[graphql] case class ServiceInput(
  title: String,
  price: Double,
  mu: String,
  vat: Double,
  group: Option[String],
  konto: Option[String]
)
private[graphql] object ServiceInput:
  def toFields(i: ServiceInput): gateway.ServiceFields =
    gateway.ServiceFields(i.title, i.price, i.mu, i.vat, i.group.getOrElse(""), i.konto.getOrElse(""))

/** A payment recorded against an invoice, mirroring the upstream `invoice-sent-p` row. */
final private[graphql] case class Payment(
  id: Long,
  invoiceId: Long,
  dateOf: String,
  amount: Double,
  paymentMethod: Long,
  note: String
)
private[graphql] object Payment:
  def from(p: gateway.Payment): Payment =
    Payment(p.id, p.id_invoice_sent, p.date_of, p.amount, p.id_payment_method, p.note)

/** Input for recording a payment. `dateOf` is ISO `YYYY-MM-DD`; `paymentMethod` defaults to the UI's default (1) when
  * omitted; `note` is optional.
  */
final private[graphql] case class PaymentInput(
  invoiceId: Long,
  dateOf: String,
  amount: Double,
  paymentMethod: Option[Long],
  note: Option[String]
)
private[graphql] object PaymentInput:
  def toFields(i: PaymentInput): gateway.PaymentFields =
    gateway.PaymentFields(i.invoiceId, i.dateOf, i.amount, i.paymentMethod.getOrElse(1L), i.note.getOrElse(""))

/** Input for one invoice line item. `mu` (unit) defaults to `kos`; `discount` to 0. */
final private[graphql] case class LineInput(
  title: String,
  qty: Double,
  price: Double,
  vat: Double,
  mu: Option[String],
  discount: Option[Double]
)
private[graphql] object LineInput:
  def toFields(i: LineInput): gateway.LineFields =
    gateway.LineFields(i.title, i.qty, i.price, i.vat, i.mu.getOrElse("kos"), i.discount.getOrElse(0.0))

/** Input for creating/updating an invoice head. Dates are **ISO `YYYY-MM-DD`** (the gateway converts to the SI format
  * the upstream insert requires); `dateServed` defaults to `dateSent`. `lines` is optional inline creation — omit and
  * use the line mutations to build up an invoice incrementally.
  */
final private[graphql] case class InvoiceInput(
  dateSent: String,
  dateToPay: String,
  partnerId: Long,
  dateServed: Option[String],
  lines: Option[List[LineInput]]
)
private[graphql] object InvoiceInput:
  def toFields(i: InvoiceInput): gateway.InvoiceFields =
    gateway.InvoiceFields(i.dateSent, i.dateToPay, i.partnerId, i.dateServed)
