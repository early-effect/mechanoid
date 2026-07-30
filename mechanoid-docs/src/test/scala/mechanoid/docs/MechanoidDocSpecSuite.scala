package mechanoid.docs

import specular.ExampleRunner
import specular.ziotest.DocSpecSuite
import specular.ziotest.DocTestInterpreter
import zio.test.*

/** DocSpecs that touch `Clock` (EventStore timestamps, timeouts, producing sleeps) need a live clock. */
trait MechanoidDocSpecSuite extends DocSpecSuite:
  override def spec: Spec[TestEnvironment, Any] =
    DocTestInterpreter.specOf(this).provideLayer(ExampleRunner.live) @@ TestAspect.withLiveClock
