package mechanoid.runtime.timeout

import zio.*
import zio.test.*
import mechanoid.persistence.timeout.{InMemoryTimeoutStore, TimeoutStore}

object DurableTimeoutStrategySpec extends ZIOSpecDefault:

  def spec = suite("DurableTimeoutStrategy")(
    suite("make")(
      test("creates a strategy from a TimeoutStore") {
        val store    = new InMemoryTimeoutStore[String]
        val strategy = DurableTimeoutStrategy.make[String](store)
        assertTrue(strategy != null)
      }
    ),
    suite("schedule")(
      test("persists timeout to store") {
        for
          store <- ZIO.succeed(new InMemoryTimeoutStore[String])
          strategy = DurableTimeoutStrategy.make[String](store)
          _ <- strategy.schedule("fsm-1", 12345, 1L, 100.millis, ZIO.unit)
          scheduled = store.getAll
        yield assertTrue(
          scheduled.contains("fsm-1"),
          scheduled("fsm-1").stateHash == 12345,
          scheduled("fsm-1").sequenceNr == 1L,
        )
      },
      test("computes deadline from current time plus duration") {
        for
          store <- ZIO.succeed(new InMemoryTimeoutStore[String])
          strategy = DurableTimeoutStrategy.make[String](store)
          now <- Clock.instant
          _   <- strategy.schedule("fsm-1", 123, 1L, 1.second, ZIO.unit)
          scheduled        = store.getAll("fsm-1")
          expectedDeadline = now.plusMillis(1000)
        yield assertTrue(
          // Allow some tolerance for timing
          scheduled.deadline.toEpochMilli >= expectedDeadline.toEpochMilli - 100,
          scheduled.deadline.toEpochMilli <= expectedDeadline.toEpochMilli + 100,
        )
      },
      test("ignores onTimeout callback (sweeper handles firing)") {
        for
          store <- ZIO.succeed(new InMemoryTimeoutStore[String])
          strategy = DurableTimeoutStrategy.make[String](store)
          ref <- Ref.make(false)
          // Schedule with a callback that sets ref to true
          _ <- strategy.schedule("fsm-1", 123, 1L, 1.millis, ref.set(true))
          // Advance time past the duration - callback should NOT be invoked
          _     <- TestClock.adjust(10.millis)
          _     <- ZIO.yieldNow
          fired <- ref.get
        yield assertTrue(!fired) // Callback is NOT used by DurableTimeoutStrategy
      },
      test("overwrites existing timeout for same instance") {
        for
          store <- ZIO.succeed(new InMemoryTimeoutStore[String])
          strategy = DurableTimeoutStrategy.make[String](store)
          _ <- strategy.schedule("fsm-1", 111, 1L, 100.millis, ZIO.unit)
          _ <- strategy.schedule("fsm-1", 222, 2L, 200.millis, ZIO.unit)
          scheduled = store.getAll
        yield assertTrue(
          scheduled.size == 1,
          scheduled("fsm-1").stateHash == 222,
          scheduled("fsm-1").sequenceNr == 2L,
        )
      },
    ),
    suite("cancel")(
      test("removes timeout from store") {
        for
          store <- ZIO.succeed(new InMemoryTimeoutStore[String])
          strategy = DurableTimeoutStrategy.make[String](store)
          _ <- strategy.schedule("fsm-1", 123, 1L, 100.millis, ZIO.unit)
          _ <- strategy.cancel("fsm-1")
          scheduled = store.getAll
        yield assertTrue(!scheduled.contains("fsm-1"))
      },
      test("is idempotent for non-existent instance") {
        for
          store <- ZIO.succeed(new InMemoryTimeoutStore[String])
          strategy = DurableTimeoutStrategy.make[String](store)
          _ <- strategy.cancel("non-existent")
        yield assertTrue(true)
      },
    ),
    suite("layer")(
      test("provides TimeoutStrategy from TimeoutStore") {
        for
          store <- ZIO.succeed(new InMemoryTimeoutStore[String])
          _     <- TimeoutStrategy
            .schedule[String]("fsm-1", 123, 1L, 100.millis, ZIO.unit)
            .provide(DurableTimeoutStrategy.layer[String], ZLayer.succeed[TimeoutStore[String]](store))
          scheduled = store.getAll
        yield assertTrue(scheduled.contains("fsm-1"))
      },
      test("TimeoutStrategy.durable convenience method provides layer") {
        for
          store <- ZIO.succeed(new InMemoryTimeoutStore[String])
          _     <- TimeoutStrategy
            .schedule[String]("fsm-1", 123, 1L, 100.millis, ZIO.unit)
            .provide(TimeoutStrategy.durable[String], ZLayer.succeed[TimeoutStore[String]](store))
          scheduled = store.getAll
        yield assertTrue(scheduled.contains("fsm-1"))
      },
    ),
  ) @@ TestAspect.timeout(10.seconds)

end DurableTimeoutStrategySpec
