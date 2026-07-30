package mechanoid.runtime.timeout

import zio.*
import zio.test.*
import mechanoid.persistence.timeout.TimeoutStore
import mechanoid.stores as liveStores

object DurableTimeoutStrategySpec extends ZIOSpecDefault:

  def spec = suite("DurableTimeoutStrategy")(
    suite("make")(
      test("creates a strategy from a TimeoutStore") {
        for
          store    <- liveStores.InMemoryTimeoutStore.make[String]
          strategy <- ZIO.succeed(DurableTimeoutStrategy.make[String](store))
        yield assertTrue(strategy != null)
      }
    ),
    suite("schedule")(
      test("persists timeout to store") {
        for
          store <- liveStores.InMemoryTimeoutStore.make[String]
          strategy = DurableTimeoutStrategy.make[String](store)
          _         <- strategy.schedule("fsm-1", 12345, 1L, 100.millis, ZIO.unit)
          scheduled <- store.getAll
        yield assertTrue(
          scheduled.contains("fsm-1"),
          scheduled("fsm-1").stateHash == 12345,
          scheduled("fsm-1").sequenceNr == 1L,
        )
      },
      test("computes deadline from current time plus duration") {
        for
          store <- liveStores.InMemoryTimeoutStore.make[String]
          strategy = DurableTimeoutStrategy.make[String](store)
          now       <- Clock.instant
          _         <- strategy.schedule("fsm-1", 123, 1L, 1.second, ZIO.unit)
          scheduled <- store.get("fsm-1")
          expectedDeadline = now.plusMillis(1000)
        yield assertTrue(
          scheduled.isDefined,
          scheduled.get.deadline.toEpochMilli >= expectedDeadline.toEpochMilli - 100,
          scheduled.get.deadline.toEpochMilli <= expectedDeadline.toEpochMilli + 100,
        )
      },
      test("overwrites existing timeout for same instance when generation changes") {
        for
          store <- liveStores.InMemoryTimeoutStore.make[String]
          strategy = DurableTimeoutStrategy.make[String](store)
          _         <- strategy.schedule("fsm-1", 111, 1L, 100.millis, ZIO.unit)
          _         <- strategy.schedule("fsm-1", 222, 2L, 200.millis, ZIO.unit)
          scheduled <- store.getAll
        yield assertTrue(
          scheduled.size == 1,
          scheduled("fsm-1").stateHash == 222,
          scheduled("fsm-1").sequenceNr == 2L,
        )
      },
      test("preserves absolute deadline when recovering the same generation") {
        for
          store <- liveStores.InMemoryTimeoutStore.make[String]
          strategy = DurableTimeoutStrategy.make[String](store)
          now   <- Clock.instant
          _     <- store.schedule("fsm-1", 123, 5L, now.plusSeconds(60))
          _     <- strategy.schedule("fsm-1", 123, 5L, 1.hour, ZIO.unit)
          after <- store.get("fsm-1")
        yield assertTrue(
          after.isDefined,
          after.get.deadline == now.plusSeconds(60),
        )
      },
    ),
    suite("cancel")(
      test("removes timeout from store") {
        for
          store <- liveStores.InMemoryTimeoutStore.make[String]
          strategy = DurableTimeoutStrategy.make[String](store)
          _         <- strategy.schedule("fsm-1", 123, 1L, 100.millis, ZIO.unit)
          _         <- strategy.cancel("fsm-1")
          scheduled <- store.getAll
        yield assertTrue(!scheduled.contains("fsm-1"))
      },
      test("is idempotent for non-existent instance") {
        for
          store <- liveStores.InMemoryTimeoutStore.make[String]
          strategy = DurableTimeoutStrategy.make[String](store)
          _ <- strategy.cancel("non-existent")
        yield assertTrue(true)
      },
    ),
    suite("layer")(
      test("provides TimeoutStrategy from TimeoutStore") {
        for
          store <- liveStores.InMemoryTimeoutStore.make[String]
          _     <- TimeoutStrategy
            .schedule[String]("fsm-1", 123, 1L, 100.millis, ZIO.unit)
            .provide(DurableTimeoutStrategy.layer[String], ZLayer.succeed[TimeoutStore[String]](store))
          scheduled <- store.getAll
        yield assertTrue(scheduled.contains("fsm-1"))
      },
      test("TimeoutStrategy.durable convenience method provides layer") {
        for
          store <- liveStores.InMemoryTimeoutStore.make[String]
          _     <- TimeoutStrategy
            .schedule[String]("fsm-1", 123, 1L, 100.millis, ZIO.unit)
            .provide(TimeoutStrategy.durable[String], ZLayer.succeed[TimeoutStore[String]](store))
          scheduled <- store.getAll
        yield assertTrue(scheduled.contains("fsm-1"))
      },
    ),
  ) @@ TestAspect.timeout(10.seconds)

end DurableTimeoutStrategySpec
