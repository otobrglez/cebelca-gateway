package biz.cebelca.gateway.graphql

import zio.*
import zio.http.*
import zio.metrics.connectors.prometheus.PrometheusPublisher

import java.nio.charset.StandardCharsets

object MetricsServer:
  private val route: Routes[PrometheusPublisher, Response] = Routes(
    Method.GET / "metrics" -> handler {
      ZIO.serviceWithZIO[PrometheusPublisher](_.get).map { text =>
        Response(
          status = Status.Ok,
          headers = Headers(Header.ContentType(MediaType.text.plain)),
          body = Body.fromString(text, StandardCharsets.UTF_8)
        )
      }
    }
  )

  def serve(port: Int): RIO[PrometheusPublisher, Nothing] = for
    _       <- ZIO.log(s"Metrics serving on :$port/metrics")
    nothing <- Server.serve(route).provideSome[PrometheusPublisher](Server.defaultWithPort(port))
  yield nothing
