package biz.cebelca.gateway

/** Typed failures surfaced to callers (and, later, GraphQL resolvers). */
sealed trait CebelcaError extends Throwable:
  override def getMessage: String = this match
    case CebelcaError.Validation(fields) => s"validation error: ${fields.mkString(", ")}"
    case CebelcaError.RowError(msg)       => s"row error: $msg"
    case CebelcaError.Http(code, body)    => s"HTTP $code: ${body.take(200)}"
    case CebelcaError.Decode(msg, raw)    => s"decode error: $msg (raw: ${raw.take(200)})"
    case CebelcaError.Transport(cause)    => s"transport error: ${cause.getMessage}"

object CebelcaError:
  /** `["validation", {"field":"required", ...}]` (HTTP 403). */
  final case class Validation(fields: Map[String, String]) extends CebelcaError
  /** `[[{"err": "..."}]]` — row-level error inside a 200. */
  final case class RowError(message: String) extends CebelcaError
  /** Non-2xx that isn't a validation error. */
  final case class Http(code: Int, body: String) extends CebelcaError
  /** Body was not parseable as the expected shape. */
  final case class Decode(message: String, raw: String) extends CebelcaError
  /** Connection failure / client threw. */
  final case class Transport(cause: Throwable) extends CebelcaError
