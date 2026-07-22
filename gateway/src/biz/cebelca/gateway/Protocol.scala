package biz.cebelca.gateway

import zio.*
import zio.http.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

final case class Cmd private (
  resource: String,
  method: String,
  args: Map[String, String] = Map.empty,
  format: Option[String] = None,
  explore: Boolean = false
):
  def withArgs(kvs: (String, String)*): Cmd = copy(args = args ++ kvs.toMap)

  override def toString: String =
    s"$resource / $method ${args.map((k, v) => s"$k: \"$v\"").mkString(", ")}"

object Cmd:
  def select(resource: String): Cmd              = Cmd(resource, "select-all")
  def selectOne(resource: String, id: Long): Cmd = Cmd(resource, "select-one", Map("id" -> id.toString))

  /** `page = -1` returns every page (unpaged); a non-negative value selects that page. The method requires `a page`,
    * rejecting its absence with a `page: required` validation error.
    */
  def selectAllSafe(resource: String, filter: String, search: Option[String] = None, page: Int = -1): Cmd =
    Cmd(
      resource,
      "select-all-safe",
      Map("filter" -> filter, "page" -> page.toString) ++ search.filter(_.nonEmpty).map("search" -> _)
    )

  /** Filtered select, as used by the invoice AND services lists. Requires `filter`, `company`, `page`. `datefrom` /
    * `dateto` are optional bounds on `date_sent`, in **ISO `YYYY-MM-DD`** (note: not the SI `DD.MM.YYYY` used by
    * inserts); `search` is an optional case-insensitive substring match on the row's title. `page = -1` returns every
    * page; `company = 0` = all companies. (Unlike `select-all`, this method honours `search`.)
    */
  def selectAllBy(
    resource: String,
    filter: String = "all",
    company: Long = 0,
    page: Int = -1,
    dateFrom: Option[String] = None,
    dateTo: Option[String] = None,
    search: Option[String] = None
  ): Cmd                                                     =
    Cmd(
      resource,
      "select-all-by",
      Map("filter" -> filter, "company" -> company.toString, "page" -> page.toString)
        ++ dateFrom.filter(_.nonEmpty).map("datefrom" -> _)
        ++ dateTo.filter(_.nonEmpty).map("dateto" -> _)
        ++ search.filter(_.nonEmpty).map("search" -> _)
    )
  def insert(resource: String, args: (String, String)*): Cmd = Cmd(resource, "insert-into", args.toMap)

  /** Full-replace update: `update-select` echoes the updated row. `id` plus every non-key field must be supplied —
    * upstream rejects a missing field with a `required` validation error (it is not a partial patch).
    */
  def update(resource: String, id: Long, args: (String, String)*): Cmd =
    Cmd(resource, "update-select", Map("id" -> id.toString) ++ args.toMap)

  /** Delete by id. The caller must supply a real id: upstream returns `OK` even for a blank/missing id, so the id must
    * never be optional at any layer above this.
    */
  def delete(resource: String, id: Long): Cmd = Cmd(resource, "delete", Map("id" -> id.toString))
  def exploreResources: Cmd                   = Cmd("", "", explore = true)
  def exploreMethods(resource: String): Cmd   = Cmd(resource, "", explore = true)

final case class Backend(baseUrl: URL):
  def toRequest(c: Cmd): Request =
    val form = c.args.map((k, v) => s"${enc(k)}=${enc(v)}").mkString("&")

    val queryParams = List[(String, Option[String])](
      "_r" -> Option(c.resource).filter(_.nonEmpty),
      "_m" -> Option(c.method).filter(_.nonEmpty),
      "_f" -> c.format.filter(_.nonEmpty),
      "_x" -> Option.when(c.explore)("_x=1")
    ).sortBy(_._1).foldLeft(QueryParams.empty) {
      case acc -> (k, Some(value)) => acc.addQueryParam(k, value)
      case acc -> _                => acc
    }

    Request
      .post(baseUrl.copy(queryParams = queryParams), Body.fromString(form))
      .addHeader(Header.ContentType(MediaType.application.`x-www-form-urlencoded`))

  private def enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)
