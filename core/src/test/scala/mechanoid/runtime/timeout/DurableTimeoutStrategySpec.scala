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
          now       <- Clock.instant
          _         <- strategy.schedule("fsm-1", "t", 12345, 1L, now.plusMillis(100), ZIO.unit)
          scheduled <- store.getAll
        yield assertTrue(
          scheduled.contains(("fsm-1", "t")),
          scheduled(("fsm-1", "t")).stateHash == 12345,
          scheduled(("fsm-1", "t")).sequenceNr == 1L,
        )
      },
      test("writes the given absolute deadline") {
        for
          store <- liveStores.InMemoryTimeoutStore.make[String]
          strategy = DurableTimeoutStrategy.make[String](store)
          now <- Clock.instant
          deadline = now.plusSeconds(1)
          _         <- strategy.schedule("fsm-1", "t", 123, 1L, deadline, ZIO.unit)
          scheduled <- store.get("fsm-1", "t")
        yield assertTrue(
          scheduled.isDefined,
          scheduled.get.deadline == deadline,
        )
      },
      test("overwrites existing timeout for same name when state hash changes") {
        for
          store <- liveStores.InMemoryTimeoutStore.make[String]
          strategy = DurableTimeoutStrategy.make[String](store)
          now       <- Clock.instant
          _         <- strategy.schedule("fsm-1", "t", 111, 1L, now.plusMillis(100), ZIO.unit)
          _         <- strategy.schedule("fsm-1", "t", 222, 2L, now.plusMillis(200), ZIO.unit)
          scheduled <- store.getAll
        yield assertTrue(
          scheduled.size == 1,
          scheduled(("fsm-1", "t")).stateHash == 222,
          scheduled(("fsm-1", "t")).sequenceNr == 2L,
        )
      },
      test("preserves absolute deadline when recovering the same name and state hash") {
        for
          store <- liveStores.InMemoryTimeoutStore.make[String]
          strategy = DurableTimeoutStrategy.make[String](store)
          now   <- Clock.instant
          _     <- store.schedule("fsm-1", "t", 123, 5L, now.plusSeconds(60))
          _     <- strategy.schedule("fsm-1", "t", 123, 5L, now.plusSeconds(3600), ZIO.unit)
          after <- store.get("fsm-1", "t")
        yield assertTrue(
          after.isDefined,
          after.get.deadline == now.plusSeconds(60),
        )
      },
      test("keeps a sibling name when scheduling another") {
        for
          store <- liveStores.InMemoryTimeoutStore.make[String]
          strategy = DurableTimeoutStrategy.make[String](store)
          now       <- Clock.instant
          _         <- strategy.schedule("fsm-1", "daily", 123, 1L, now.plusSeconds(1), ZIO.unit)
          _         <- strategy.schedule("fsm-1", "weekly", 123, 1L, now.plusSeconds(7), ZIO.unit)
          scheduled <- store.getAll
        yield assertTrue(scheduled.size == 2)
      },
    ),
    suite("cancel")(
      test("removes timeout from store") {
        for
          store <- liveStores.InMemoryTimeoutStore.make[String]
          strategy = DurableTimeoutStrategy.make[String](store)
          now       <- Clock.instant
          _         <- strategy.schedule("fsm-1", "t", 123, 1L, now.plusMillis(100), ZIO.unit)
          _         <- strategy.cancel("fsm-1")
          scheduled <- store.getAll
        yield assertTrue(scheduled.isEmpty)
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
          now   <- Clock.instant
          _     <- TimeoutStrategy
            .schedule[String]("fsm-1", "t", 123, 1L, now.plusMillis(100), ZIO.unit)
            .provide(DurableTimeoutStrategy.layer[String], ZLayer.succeed[TimeoutStore[String]](store))
          scheduled <- store.getAll
        yield assertTrue(scheduled.contains(("fsm-1", "t")))
      },
      test("TimeoutStrategy.durable convenience method provides layer") {
        for
          store <- liveStores.InMemoryTimeoutStore.make[String]
          now   <- Clock.instant
          _     <- TimeoutStrategy
            .schedule[String]("fsm-1", "t", 123, 1L, now.plusMillis(100), ZIO.unit)
            .provide(TimeoutStrategy.durable[String], ZLayer.succeed[TimeoutStore[String]](store))
          scheduled <- store.getAll
        yield assertTrue(scheduled.contains(("fsm-1", "t")))
      },
    ),
  ) @@ TestAspect.timeout(10.seconds)

end DurableTimeoutStrategySpec
