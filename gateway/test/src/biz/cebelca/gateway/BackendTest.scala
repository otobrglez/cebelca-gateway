package biz.cebelca.gateway

import zio.*
import zio.test.*
import biz.cebelca.gateway.testkit.*
import zio.http.*

object BackendTest extends GatewaySpecDefault:

  // Cmd's constructor is private, so build values through the public factory methods.
  private def genCmd: Gen[Any, Cmd] =
    val resource = Gen.fromIterable(List("invoice", "partner", "document"))
    val id       = Gen.long(1, 1000)
    val args     = Gen.listOf(Gen.fromIterable(List("arg1" -> "value1", "arg2" -> "value2"))).map(_.distinct)
    val filter   = Gen.fromIterable(List("all", "wsent", "debtors", "pasive", "disabled", "last"))
    Gen.oneOf(
      resource.map(Cmd.select),
      resource.zip(id).map(Cmd.selectOne),
      resource.zip(filter).map((r, f) => Cmd.selectAllSafe(r, f)),
      resource.map(r => Cmd.selectAllBy(r, dateFrom = Some("2026-01-01"), dateTo = Some("2026-12-31"))),
      resource.map(r => Cmd.selectAllBy(r, search = Some("consulting"))),
      resource.zip(args).map((r, kvs) => Cmd.insert(r, kvs*)),
      resource.zip(id).zip(args).map((r, i, kvs) => Cmd.update(r, i, kvs*)),
      resource.zip(id).map((r, i) => Cmd.delete(r, i)),
      Gen.const(Cmd.exploreResources),
      resource.map(Cmd.exploreMethods)
    )

  private val baseUrl: URL = URL.decode("http://backend/API").toTry.get

  def spec = suite("BackendTest")(
    test("toRequest method") {
      check(genCmd) { cmd =>
        val backend = Backend(baseUrl)
        val request = backend.toRequest(cmd)
        assertCompletes
      }
    } @@ TestAspect.samples(255) @@ TestAspect.nondeterministic,
    test("selectAllBy includes required filter/company/page args plus search") {
      val backend = Backend(baseUrl)
      val request = backend.toRequest(Cmd.selectAllBy("invoice-sent-o", search = Some("consulting")))

      for body <- request.body.asString
      yield assertTrue(
        request.url.queryParams == QueryParams("_m" -> "select-all-by", "_r" -> "invoice-sent-o"),
        body.split("&").toSet == Set("filter=all", "company=0", "page=-1", "search=consulting")
      )
    },
    test("Dates.isoToSi converts ISO to SI, passes through non-ISO") {
      assertTrue(
        Dates.isoToSi("2026-07-23") == "23.07.2026",
        Dates.isoToSi("2026-01-05") == "05.01.2026",
        // non-ISO input is returned unchanged (upstream validates)
        Dates.isoToSi("23.07.2026") == "23.07.2026",
        Dates.isoToSi("") == ""
      )
    }
  )
