package mechanoid.runtime.timeout

import zio.*
import zio.test.*

object FiberTimeoutStrategySpec extends ZIOSpecDefault:

  private def deadline(after: Duration) =
    Clock.instant.map(_.plusNanos(after.toNanos))

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
          at       <- deadline(50.millis)
          _        <- strategy.schedule("test-1", "t", 123, 1L, at, ref.set(true))
          _        <- TestClock.adjust(50.millis)
          _        <- ZIO.yieldNow
          fired    <- ref.get
        yield assertTrue(fired)
      },
      test("does not execute callback before duration") {
        for
          strategy <- FiberTimeoutStrategy.make[String]
          ref      <- Ref.make(false)
          at       <- deadline(100.millis)
          _        <- strategy.schedule("test-1", "t", 123, 1L, at, ref.set(true))
          _        <- TestClock.adjust(50.millis)
          fired    <- ref.get
        yield assertTrue(!fired)
      },
      test("replaces the fiber for the same name") {
        for
          strategy <- FiberTimeoutStrategy.make[String]
          ref1     <- Ref.make(false)
          ref2     <- Ref.make(false)
          at       <- deadline(100.millis)
          _        <- strategy.schedule("test-1", "t", 123, 1L, at, ref1.set(true))
          at2      <- deadline(100.millis)
          _        <- strategy.schedule("test-1", "t", 456, 2L, at2, ref2.set(true))
          _        <- TestClock.adjust(150.millis)
          _        <- ZIO.yieldNow
          fired1   <- ref1.get
          fired2   <- ref2.get
        yield assertTrue(!fired1, fired2)
      },
      test("keeps a different name armed on the same instance") {
        for
          strategy <- FiberTimeoutStrategy.make[String]
          ref1     <- Ref.make(false)
          ref2     <- Ref.make(false)
          at       <- deadline(50.millis)
          _        <- strategy.schedule("test-1", "daily", 123, 1L, at, ref1.set(true))
          _        <- strategy.schedule("test-1", "weekly", 123, 1L, at, ref2.set(true))
          _        <- TestClock.adjust(50.millis)
          _        <- ZIO.yieldNow
          fired1   <- ref1.get
          fired2   <- ref2.get
        yield assertTrue(fired1, fired2)
      },
      test("allows multiple timeouts for different instances") {
        for
          strategy <- FiberTimeoutStrategy.make[String]
          ref1     <- Ref.make(false)
          ref2     <- Ref.make(false)
          at       <- deadline(50.millis)
          _        <- strategy.schedule("instance-1", "t", 123, 1L, at, ref1.set(true))
          _        <- strategy.schedule("instance-2", "t", 456, 1L, at, ref2.set(true))
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
          at       <- deadline(100.millis)
          _        <- strategy.schedule("test-1", "t", 123, 1L, at, ref.set(true))
          _        <- strategy.cancel("test-1")
          _        <- TestClock.adjust(150.millis)
          fired    <- ref.get
        yield assertTrue(!fired)
      },
      test("cancels one name without the other") {
        for
          strategy <- FiberTimeoutStrategy.make[String]
          ref1     <- Ref.make(false)
          ref2     <- Ref.make(false)
          at       <- deadline(50.millis)
          _        <- strategy.schedule("test-1", "daily", 123, 1L, at, ref1.set(true))
          _        <- strategy.schedule("test-1", "weekly", 123, 1L, at, ref2.set(true))
          _        <- strategy.cancel("test-1", "daily")
          _        <- TestClock.adjust(50.millis)
          _        <- ZIO.yieldNow
          fired1   <- ref1.get
          fired2   <- ref2.get
        yield assertTrue(!fired1, fired2)
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
          at       <- deadline(50.millis)
          _        <- strategy.schedule("instance-1", "t", 123, 1L, at, ref1.set(true))
          _        <- strategy.schedule("instance-2", "t", 456, 1L, at, ref2.set(true))
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
          at    <- deadline(50.millis)
          _     <- TimeoutStrategy.schedule("test", "t", 123, 1L, at, ref.set(true))
          _     <- TestClock.adjust(50.millis)
          _     <- ZIO.yieldNow
          fired <- ref.get
        yield assertTrue(fired)

        program.provide(FiberTimeoutStrategy.layer[String])
      },
      test("TimeoutStrategy.fiber convenience method provides layer") {
        val program = for
          ref   <- Ref.make(false)
          at    <- deadline(50.millis)
          _     <- TimeoutStrategy.schedule("test", "t", 123, 1L, at, ref.set(true))
          _     <- TestClock.adjust(50.millis)
          _     <- ZIO.yieldNow
          fired <- ref.get
        yield assertTrue(fired)

        program.provide(TimeoutStrategy.fiber[String])
      },
      test("TimeoutStrategy.cancel accessor method cancels timeout") {
        val program = for
          ref   <- Ref.make(false)
          at    <- deadline(100.millis)
          _     <- TimeoutStrategy.schedule("test", "t", 123, 1L, at, ref.set(true))
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
        for
          strategy <- FiberTimeoutStrategy.make[String]
          ref      <- Ref.make(false)
          at       <- deadline(10.millis)
          result   <- strategy.schedule("test-return", "t", 123, 1L, at, ref.set(true))
        yield assertTrue(result == ())
      }
    ),
  ) @@ TestAspect.timeout(10.seconds)

end FiberTimeoutStrategySpec
