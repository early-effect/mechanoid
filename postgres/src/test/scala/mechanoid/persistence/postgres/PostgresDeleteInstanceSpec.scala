package mechanoid.persistence.postgres

import zio.*
import zio.test.*
import mechanoid.*
import mechanoid.PostgresTestContainer
import mechanoid.persistence.*
import mechanoid.persistence.lock.*
import mechanoid.persistence.timeout.TimeoutStore

object PostgresDeleteInstanceSpec extends ZIOSpecDefault:

  enum Leaf derives Finite:
    case Open, Done

  enum Signal derives Finite:
    case Go

  private val stores =
    PostgresTestContainer.DataSourceProvider.transactor >>> (
      PostgresEventStore.makeLayer[Leaf, Signal] ++
        PostgresInstanceIndex.layer ++
        PostgresTimeoutStore.layer ++
        PostgresInstanceLock.layer
    )

  def spec = suite("Postgres delete instance")(
    test("delete clears the log, snapshot, alias, index, timeout, and lock") {
      for
        events   <- ZIO.service[EventStore[String, Leaf, Signal]]
        index    <- ZIO.service[InstanceIndex[String]]
        timeouts <- ZIO.service[TimeoutStore[String]]
        lock     <- ZIO.service[FSMInstanceLock[String]]
        now      <- Clock.instant
        a      = s"del-${java.util.UUID.randomUUID()}"
        b      = s"keep-${java.util.UUID.randomUUID()}"
        aliasA = Alias("campaign", a)
        aliasB = Alias("campaign", b)
        keyA   = IndexKey("assignee", a)
        keyB   = IndexKey("assignee", b)
        row    = IndexMeta("Done", now, now, None)
        _ <- events.append(a, Signal.Go, 0L)
        _ <- events.saveSnapshot(FSMSnapshot(a, Leaf.Done, 1L, now))
        _ <- events.append(b, Signal.Go, 0L)
        _ <- events.saveSnapshot(FSMSnapshot(b, Leaf.Done, 1L, now))
        _ <- index.bind(aliasA, a)
        _ <- index.bind(aliasB, b)
        _ <- index.bindIndexes(Chunk(keyA), a, row)
        _ <- index.bindIndexes(Chunk(keyB), b, row)
        _ <- timeouts.schedule(a, "tick", 1, 1L, now.plusMillis(50))
        _ <- timeouts.schedule(b, "tick", 1, 1L, now.plusMillis(50))
        _ <- lock.tryAcquire(a, "other", 30.seconds, now)
        _ <- lock.tryAcquire(b, "other", 30.seconds, now)
        _ <- FSMRuntime
          .delete[String, Leaf, Signal](a)
          .provideSome[
            EventStore[String, Leaf, Signal] & InstanceIndex[String] & TimeoutStore[String] & FSMInstanceLock[String]
          ](
            DurableTimeoutStrategy.layer[String] ++ LockingStrategy.distributed[String]
          )
        goneEvents <- events.loadEvents(a).runCollect
        goneSnap   <- events.loadSnapshot(a)
        goneState  <- events.currentState(a)
        goneSeq    <- events.highestSequenceNr(a)
        again      <- events.append(a, Signal.Go, 0L)
        goneAlias  <- index.resolve(aliasA)
        goneKeys   <- index.indexesOf(a)
        goneTimer  <- timeouts.get(a)
        goneLock   <- lock.get(a, now)
        keptSnap   <- events.loadSnapshot(b)
        keptAlias  <- index.resolve(aliasB)
        keptKeys   <- index.indexesOf(b)
        keptTimer  <- timeouts.get(b)
        keptLock   <- lock.get(b, now)
      yield assertTrue(
        goneEvents.isEmpty,
        goneSnap.isEmpty,
        goneState.isEmpty,
        goneSeq == 0L,
        again == 1L,
        goneAlias.isEmpty,
        goneKeys.isEmpty,
        goneTimer.isEmpty,
        goneLock.isEmpty,
        keptSnap.isDefined,
        keptAlias.contains(b),
        keptKeys.toSet == Set(keyB),
        keptTimer.size == 1,
        keptLock.isDefined,
      )
    },
    test("delete of an unknown id succeeds") {
      for
        events <- ZIO.service[EventStore[String, Leaf, Signal]]
        id = s"missing-${java.util.UUID.randomUUID()}"
        _ <- FSMRuntime
          .delete[String, Leaf, Signal](id)
          .provideSome[EventStore[
            String,
            Leaf,
            Signal,
          ] & InstanceIndex[String] & TimeoutStore[String] & FSMInstanceLock[String]](
            DurableTimeoutStrategy.layer[String] ++ LockingStrategy.distributed[String]
          )
        seq <- events.highestSequenceNr(id)
      yield assertTrue(seq == 0L)
    },
  ).provideShared(stores) @@ TestAspect.sequential @@ TestAspect.withLiveClock
end PostgresDeleteInstanceSpec
