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

object Cmd:
  def select(resource: String): Cmd                          = Cmd(resource, "select-all")
  def selectOne(resource: String, id: Long): Cmd             = Cmd(resource, "select-one", Map("id" -> id.toString))
  def insert(resource: String, args: (String, String)*): Cmd = Cmd(resource, "insert-into", args.toMap)
  def exploreResources: Cmd                                  = Cmd("", "", explore = true)
  def exploreMethods(resource: String): Cmd                  = Cmd(resource, "", explore = true)

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
