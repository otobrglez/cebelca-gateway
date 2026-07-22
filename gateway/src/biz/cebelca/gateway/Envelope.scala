package biz.cebelca.gateway

import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json

/** Decodes the Cebelca response envelope. The server returns JSON under a `Content-Type: text/html` header, so we parse
  * by convention, not by header.
  *
  * Shapes:
  *   - success: `[[ {row}, {row} ]]` → rows at result[0]
  *   - validation: `["validation", {"field":"required", ...}]` (HTTP 403)
  *   - row error: `[[ {"err": "..."} ]]`
  */
object Envelope:

  /** Decode a response body into a list of rows of type `A`. */
  def rows[A](response: Response)(using JsonDecoder[A]): IO[CebelcaError, List[A]] =
    body(response).flatMap(parse[A])

  /** Decode and take the first row (e.g. inserts returning `[[{"id":N}]]`). */
  def first[A](response: Response)(using JsonDecoder[A]): IO[CebelcaError, A] =
    rows[A](response).flatMap {
      case head :: _ => ZIO.succeed(head)
      case Nil       => ZIO.fail(CebelcaError.Decode("expected at least one row, got empty", ""))
    }

  /** Decode a mutation acknowledgement, e.g. `delete` returning `[[{"OK":"res"}]]`. Any other (non-error) shape means
    * the ack was absent — surfaced as `false` rather than a decode failure. Error envelopes still fail as usual.
    */
  def ack(response: Response): IO[CebelcaError, Boolean] =
    rows[Map[String, String]](response).map(_.headOption.flatMap(_.get("OK")).contains("res"))

  private def body(response: Response): IO[CebelcaError, String] =
    response.body.asString.mapBoth(CebelcaError.Transport(_), _ -> response.status.code).flatMap {
      case raw -> code if code >= 400 && code != 403 => ZIO.fail(CebelcaError.Http(code, raw))
      case raw -> _                                  => ZIO.succeed(raw)
    }

  private def parse[A](raw: String)(using JsonDecoder[A]): IO[CebelcaError, List[A]] =
    ZIO
      .fromEither(toJson(raw))
      .mapError(CebelcaError.Decode(_, raw))
      .flatMap(interpret[A](_, raw))

  /** Tolerate single-quoted pseudo-JSON the server occasionally emits. */
  private def toJson(raw: String): Either[String, Json] =
    raw.fromJson[Json].orElse(raw.trim.replace('\'', '"').fromJson[Json])

  private def interpret[A](json: Json, raw: String)(using JsonDecoder[A]): IO[CebelcaError, List[A]] = json match
    // ["validation", { ...fields... }]
    case Json.Arr(elems) if elems.headOption.contains(Json.Str("validation")) =>
      val fields = elems
        .lift(1)
        .flatMap(_.asObject)
        .map(_.fields.map((k, v) => k -> v.asString.getOrElse(v.toString)).toMap)
        .getOrElse(Map.empty)
      ZIO.fail(CebelcaError.Validation(fields))

    // [[ {row}, ... ]]  — unwrap the outer array
    case Json.Arr(outer) =>
      val inner = outer.headOption match
        case Some(Json.Arr(rows)) => rows
        case _                    => Chunk.empty
      // [[ {"err": "..."} ]]
      inner.headOption.flatMap(_.asObject).flatMap(_.get("err")).flatMap(_.asString) match
        case Some(err) => ZIO.fail(CebelcaError.RowError(err))
        case None      =>
          ZIO
            .foreach(inner.toList)(j => ZIO.fromEither(j.as[A]))
            .mapError(CebelcaError.Decode(_, raw))

    case other =>
      ZIO.fail(CebelcaError.Decode(s"unexpected top-level JSON: ${other.getClass.getSimpleName}", raw))
