package biz.cebelca.gateway

import zio.json.*

/** Date helpers for the SI/ISO split: the API's date *filters* take ISO `YYYY-MM-DD`, but its
  * *inserts* (`invoice-sent`) require SI `DD.MM.YYYY` — feeding ISO to an insert silently stores an
  * empty date. The GraphQL API standardises on ISO everywhere and converts to SI on writes.
  */
object Dates:
  /** `2026-07-23` → `23.07.2026`. A non-ISO input is returned unchanged (upstream does the validating). */
  def isoToSi(iso: String): String =
    iso.split("-") match
      case Array(y, m, d) => s"$d.$m.$y"
      case _              => iso

final case class Partner(
  id: Long,
  name: String,
  street: String,
  postal: String,
  city: String,
  vatid: String,
  country: String,
  lang: String,
  disabled: Int
) derives JsonDecoder

/** Upstream `date_payed` is polymorphic: the integer `0` when unpaid, or a `"YYYY-MM-DD"` string when paid. This
  * decoder normalises it to `Option[String]` (`None` when unpaid), so it doubles as the authoritative paid signal —
  * unlike the `payment` field, which is the payment *terms/method*, not the paid status.
  */
opaque type PaidDate = Option[String]
object PaidDate:
  def value(p: PaidDate): Option[String] = p
  given JsonDecoder[PaidDate] = JsonDecoder[zio.json.ast.Json].map {
    case zio.json.ast.Json.Str(s) if s.nonEmpty && s != "0" => Some(s)
    case _                                                  => None
  }

final case class InvoiceHead(
  id: Long,
  title: String,
  date_sent: String,
  date_to_pay: String,
  date_served: String,
  id_partner: Long,
  payment: String,
  @jsonField("date_payed") date_payed: PaidDate,
  fiscalized: Option[String]
) derives JsonDecoder

final case class InvoiceLine(
  id: Long,
  id_invoice_sent: Long,
  title: String,
  qty: Double,
  mu: String,
  price: Double,
  vat: Double,
  discount: Double
) derives JsonDecoder

/** A service / pricelist entry, as shown on the UI's "Storitve" page. Backed by the `invoice-sent-o` resource
  * (the API's "Items&services for sent invoice bodies"), whose `select-all` returns the pricelist rows.
  */
final case class Service(
  id: Long,
  object_title: String,
  price: Double,
  measure_unit: String,
  vat: Double,
  @jsonField("group_") group: String,
  konto: String
) derives JsonDecoder

/** The id-only row an `insert-into` returns: `[[{"id":N}]]`. */
final case class IdRow(id: Long) derives JsonDecoder

/** The `select-next-title` response row: `[[{"proposed_title":"26-0004"}]]` — the recommended next invoice number. */
final case class ProposedTitle(proposed_title: String) derives JsonDecoder

/** `duplicate-invoice` returns the new id under the un-aliased SQLite key `last_insert_rowid()` (a server leak — every
  * other insert returns `{"id":N}`). This decoder maps that ugly key back to a clean `id`.
  */
final case class RowId(@jsonField("last_insert_rowid()") id: Long) derives JsonDecoder

/** A payment recorded against a sent invoice (`invoice-sent-p`). `date_of` is ISO `YYYY-MM-DD`; `id_payment_method`
  * is a free-form method id (the API defines no fixed enum — the UI's cash/bank/etc. options map to integers).
  */
final case class Payment(
  id: Long,
  id_invoice_sent: Long,
  date_of: String,
  amount: Double,
  id_payment_method: Long,
  note: String
) derives JsonDecoder

/** The writable fields of a payment, bundled like [[ServiceFields]]/[[PartnerFields]]. Maps to the upstream
  * `invoice-sent-p` wire names. `paymentMethod` defaults to 1 (the UI's default method); `note` is optional.
  */
final case class PaymentFields(
  invoiceId: Long,
  dateOf: String,
  amount: Double,
  paymentMethod: Long = 1,
  note: String = ""
)

/** The writable fields of a service, bundled so create/update take one argument instead of a wide positional list.
  * Maps to the upstream `invoice-sent-o` wire names in [[CebelcaAPI]].
  */
final case class ServiceFields(title: String, price: Double, mu: String, vat: Double, group: String, konto: String)

/** The writable fields of a partner, bundled so create/update take one argument instead of a wide positional list.
  * Only `name` is required upstream; the rest default to empty. Maps to the upstream `partner` wire names.
  */
final case class PartnerFields(
  name: String,
  street: String = "",
  postal: String = "",
  city: String = "",
  vatid: String = "",
  country: String = "",
  lang: String = ""
)

/** The writable fields of an invoice head. Dates are **ISO `YYYY-MM-DD`** here; [[CebelcaAPI]] converts them to the
  * SI `DD.MM.YYYY` the upstream insert requires. `dateServed` defaults to `dateSent` when omitted.
  */
final case class InvoiceFields(
  dateSent: String,
  dateToPay: String,
  partnerId: Long,
  dateServed: Option[String] = None
)

/** The writable fields of an invoice line item (`invoice-sent-b`). `mu` (unit) and `discount` are optional upstream. */
final case class LineFields(
  title: String,
  qty: Double,
  price: Double,
  vat: Double,
  mu: String = "kos",
  discount: Double = 0.0
)
