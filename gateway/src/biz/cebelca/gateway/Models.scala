package biz.cebelca.gateway

import zio.json.*

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
