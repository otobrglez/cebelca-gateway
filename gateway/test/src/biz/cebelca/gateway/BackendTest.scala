package biz.cebelca.gateway

import zio.*
import zio.test.*
import biz.cebelca.gateway.testkit.*
import zio.http.URL

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
      resource.zip(args).map((r, kvs) => Cmd.insert(r, kvs*)),
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
    } @@ TestAspect.samples(255) @@ TestAspect.nondeterministic
  )
