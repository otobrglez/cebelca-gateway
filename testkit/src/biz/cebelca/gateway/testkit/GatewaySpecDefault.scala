package biz.cebelca.gateway.testkit

import zio.*
import zio.Runtime.{removeDefaultLoggers, setConfigProvider}
import zio.logging.backend.SLF4J
import zio.test.*

abstract class GatewaySpecDefault extends ZIOSpecDefault:

  override val bootstrap: ZLayer[Any, Any, TestEnvironment] =
    setConfigProvider(ConfigProvider.envProvider) >>>
      removeDefaultLoggers >>> SLF4J.slf4j >>>
      testEnvironment

  override def aspects: Chunk[TestAspectAtLeastR[TestEnvironment]] =
    super.aspects ++ Chunk(TestAspect.withLiveEnvironment, TestAspect.sequential)
