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

final case class InvoiceHead(
  id: Long,
  title: String,
  date_sent: String,
  date_to_pay: String,
  date_served: String,
  id_partner: Long,
  payment: String,
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

final case class Item(
  id: Long,
  code: String,
  descr: String,
  price: Double,
  unit: String,
  tax: Double,
  disabled: Int
) derives JsonDecoder
