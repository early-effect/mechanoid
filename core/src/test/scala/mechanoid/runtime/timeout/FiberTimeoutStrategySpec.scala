package mechanoid.runtime.timeout

import zio.*
import zio.test.*

object FiberTimeoutStrategySpec extends ZIOSpecDefault:

  def spec = suite("FiberTimeoutStrategy")(
    suite("make")(
      test("creates a new strategy instance") {
        for strategy <- FiberTimeoutStrategy.make[String]
        yield assertTrue(strategy != null)
      }
    ),
    suite("schedule")(
      test("executes callback after duration") {
        for
          strategy <- FiberTimeoutStrategy.make[String]
          ref      <- Ref.make(false)
          _        <- strategy.schedule("test-1", 123, 1L, 50.millis, ref.set(true))
          _        <- TestClock.adjust(50.millis)
          _        <- ZIO.yieldNow // Allow fiber to complete
          fired    <- ref.get
        yield assertTrue(fired)
      },
      test("does not execute callback before duration") {
        for
          strategy <- FiberTimeoutStrategy.make[String]
          ref      <- Ref.make(false)
          _        <- strategy.schedule("test-1", 123, 1L, 100.millis, ref.set(true))
          _        <- TestClock.adjust(50.millis)
          fired    <- ref.get
        yield assertTrue(!fired)
      },
      test("cancels existing timeout when scheduling new one for same instance") {
        for
          strategy <- FiberTimeoutStrategy.make[String]
          ref1     <- Ref.make(false)
          ref2     <- Ref.make(false)
          _        <- strategy.schedule("test-1", 123, 1L, 100.millis, ref1.set(true))
          _        <- strategy.schedule("test-1", 456, 2L, 100.millis, ref2.set(true))
          _        <- TestClock.adjust(150.millis)
          _        <- ZIO.yieldNow
          fired1   <- ref1.get
          fired2   <- ref2.get
        yield assertTrue(!fired1, fired2)
      },
      test("allows multiple timeouts for different instances") {
        for
          strategy <- FiberTimeoutStrategy.make[String]
          ref1     <- Ref.make(false)
          ref2     <- Ref.make(false)
          _        <- strategy.schedule("instance-1", 123, 1L, 50.millis, ref1.set(true))
          _        <- strategy.schedule("instance-2", 456, 1L, 50.millis, ref2.set(true))
          _        <- TestClock.adjust(50.millis)
          _        <- ZIO.yieldNow
          fired1   <- ref1.get
          fired2   <- ref2.get
        yield assertTrue(fired1, fired2)
      },
    ),
    suite("cancel")(
      test("prevents timeout from firing") {
        for
          strategy <- FiberTimeoutStrategy.make[String]
          ref      <- Ref.make(false)
          _        <- strategy.schedule("test-1", 123, 1L, 100.millis, ref.set(true))
          _        <- strategy.cancel("test-1")
          _        <- TestClock.adjust(150.millis)
          fired    <- ref.get
        yield assertTrue(!fired)
      },
      test("is idempotent for non-existent instance") {
        for
          strategy <- FiberTimeoutStrategy.make[String]
          _        <- strategy.cancel("non-existent")
        yield assertTrue(true)
      },
      test("does not affect other instances") {
        for
          strategy <- FiberTimeoutStrategy.make[String]
          ref1     <- Ref.make(false)
          ref2     <- Ref.make(false)
          _        <- strategy.schedule("instance-1", 123, 1L, 50.millis, ref1.set(true))
          _        <- strategy.schedule("instance-2", 456, 1L, 50.millis, ref2.set(true))
          _        <- strategy.cancel("instance-1")
          _        <- TestClock.adjust(50.millis)
          _        <- ZIO.yieldNow
          fired1   <- ref1.get
          fired2   <- ref2.get
        yield assertTrue(!fired1, fired2)
      },
    ),
    suite("layer")(
      test("provides TimeoutStrategy service") {
        val program = for
          ref   <- Ref.make(false)
          _     <- TimeoutStrategy.schedule("test", 123, 1L, 50.millis, ref.set(true))
          _     <- TestClock.adjust(50.millis)
          _     <- ZIO.yieldNow
          fired <- ref.get
        yield assertTrue(fired)

        program.provide(FiberTimeoutStrategy.layer[String])
      },
      test("TimeoutStrategy.fiber convenience method provides layer") {
        val program = for
          ref   <- Ref.make(false)
          _     <- TimeoutStrategy.schedule("test", 123, 1L, 50.millis, ref.set(true))
          _     <- TestClock.adjust(50.millis)
          _     <- ZIO.yieldNow
          fired <- ref.get
        yield assertTrue(fired)

        program.provide(TimeoutStrategy.fiber[String])
      },
      test("TimeoutStrategy.cancel accessor method cancels timeout") {
        val program = for
          ref   <- Ref.make(false)
          _     <- TimeoutStrategy.schedule("test", 123, 1L, 100.millis, ref.set(true))
          _     <- TimeoutStrategy.cancel("test")
          _     <- TestClock.adjust(150.millis)
          _     <- ZIO.yieldNow
          fired <- ref.get
        yield assertTrue(!fired)

        program.provide(TimeoutStrategy.fiber[String])
      },
    ),
    suite("schedule return value")(
      test("schedule returns Unit on completion") {
        // Test FiberTimeoutStrategy.scala line 51: yield ()
        for
          strategy <- FiberTimeoutStrategy.make[String]
          ref      <- Ref.make(false)
          result   <- strategy.schedule("test-return", 123, 1L, 10.millis, ref.set(true))
        yield assertTrue(result == ())
      }
    ),
  ) @@ TestAspect.timeout(10.seconds)

end FiberTimeoutStrategySpec
