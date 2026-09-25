package mechanoid.runtime

import java.time.Instant
import zio.*
import zio.test.*
import mechanoid.*
import mechanoid.persistence.*
import mechanoid.persistence.lock.LockedFSMRuntime

object DeleteInstanceSpec extends ZIOSpecDefault:

  enum Leaf derives Finite:
    case Open, Done

  enum Signal derives Finite:
    case Go

  private val machine = Machine(
    assembly[Leaf, Signal](
      Leaf.Open via Signal.Go to Leaf.Done
    )
  )

  private val aliasA = Alias("campaign", "c-a")
  private val aliasB = Alias("campaign", "c-b")
  private val keyA   = IndexKey("assignee", "me")
  private val keyB   = IndexKey("assignee", "you")

  private def meta(now: Instant) = IndexMeta("Done", now, now, None)

  private def env(
      events: EventStore[String, Leaf, Signal],
      index: InstanceIndex[String],
      timeouts: TimeoutStrategy[String],
      locking: LockingStrategy[String],
  ) =
    ZLayer.succeed[EventStore[String, Leaf, Signal]](events) ++
      ZLayer.succeed[InstanceIndex[String]](index) ++
      ZLayer.succeed[TimeoutStrategy[String]](timeouts) ++
      ZLayer.succeed[LockingStrategy[String]](locking)

  def spec = suite("Delete an instance")(
    test("fsm.delete stops the runtime and clears every row for that id") {
      for
        events   <- InMemoryEventStore.make[String, Leaf, Signal]()
        index    <- InMemoryInstanceIndex.make[String]
        timeouts <- InMemoryTimeoutStore.make[String]
        lock     <- InMemoryFSMInstanceLock.make[String]
        strategy <- DistributedLockingStrategy.make(lock)
        now      <- Clock.instant
        running  <- ZIO
          .scoped {
            FSMRuntime(
              "a",
              machine,
              Leaf.Open,
              AliasExtractor.none[Leaf],
              IndexExtractor.none[Leaf],
            ).flatMap { fsm =>
              for
                _       <- fsm.send(Signal.Go)
                _       <- fsm.saveSnapshot
                _       <- events.append("b", Signal.Go, 0L)
                _       <- events.saveSnapshot(FSMSnapshot("b", Leaf.Done, 1L, now))
                _       <- index.bind(aliasA, "a")
                _       <- index.bind(aliasB, "b")
                _       <- index.bindIndexes(Chunk(keyA), "a", meta(now))
                _       <- index.bindIndexes(Chunk(keyB), "b", meta(now))
                _       <- timeouts.schedule("a", "tick", 1, 1L, now.plusMillis(50))
                _       <- timeouts.schedule("b", "tick", 1, 1L, now.plusMillis(50))
                _       <- lock.tryAcquire("a", "other", 30.seconds, now)
                _       <- lock.tryAcquire("b", "other", 30.seconds, now)
                _       <- fsm.delete
                _       <- fsm.send(Signal.Go)
                stopped <- fsm.isRunning
              yield stopped
            }
          }
          .provide(
            env(events, index, DurableTimeoutStrategy.make(timeouts), strategy)
          )
        goneEvents <- events.loadEvents("a").runCollect
        goneSnap   <- events.loadSnapshot("a")
        goneState  <- events.currentState("a")
        goneSeq    <- events.highestSequenceNr("a")
        restarted  <- events.append("a", Signal.Go, 0L)
        goneAlias  <- index.resolve(aliasA)
        goneIndex  <- index.indexesOf("a")
        goneTimer  <- timeouts.get("a")
        goneLock   <- lock.get("a", now)
        keptEvents <- events.loadEvents("b").runCollect
        keptSnap   <- events.loadSnapshot("b")
        keptAlias  <- index.resolve(aliasB)
        keptIndex  <- index.indexesOf("b")
        keptTimer  <- timeouts.get("b")
        keptLock   <- lock.get("b", now)
      yield assertTrue(
        !running,
        goneEvents.isEmpty,
        goneSnap.isEmpty,
        goneState.isEmpty,
        goneSeq == 0L,
        restarted == 1L,
        goneAlias.isEmpty,
        goneIndex.isEmpty,
        goneTimer.isEmpty,
        goneLock.isEmpty,
        keptEvents.size == 1,
        keptSnap.isDefined,
        keptAlias.contains("b"),
        keptIndex.toSet == Set(keyB),
        keptTimer.size == 1,
        keptLock.isDefined,
      )
    },
    test("FSMRuntime.delete wipes stores without opening a runtime") {
      for
        events   <- InMemoryEventStore.make[String, Leaf, Signal]()
        index    <- InMemoryInstanceIndex.make[String]
        timeouts <- InMemoryTimeoutStore.make[String]
        lock     <- InMemoryFSMInstanceLock.make[String]
        strategy <- DistributedLockingStrategy.make(lock)
        now      <- Clock.instant
        _        <- events.append("a", Signal.Go, 0L)
        _        <- events.saveSnapshot(FSMSnapshot("a", Leaf.Done, 1L, now))
        _        <- index.bind(aliasA, "a")
        _        <- index.bindIndexes(Chunk(keyA), "a", meta(now))
        _        <- timeouts.schedule("a", "tick", 1, 1L, now.plusMillis(50))
        _        <- lock.tryAcquire("a", "other", 30.seconds, now)
        _        <- FSMRuntime
          .delete[String, Leaf, Signal]("a")
          .provide(env(events, index, DurableTimeoutStrategy.make(timeouts), strategy))
        eventsA <- events.loadEvents("a").runCollect
        snap    <- events.loadSnapshot("a")
        alias   <- index.resolve(aliasA)
        keys    <- index.indexesOf("a")
        timer   <- timeouts.get("a")
        held    <- lock.get("a", now)
        seq     <- events.highestSequenceNr("a")
      yield assertTrue(
        eventsA.isEmpty,
        snap.isEmpty,
        alias.isEmpty,
        keys.isEmpty,
        timer.isEmpty,
        held.isEmpty,
        seq == 0L,
      )
    },
    test("delete of an unknown id succeeds") {
      for
        events   <- InMemoryEventStore.make[String, Leaf, Signal]()
        index    <- InMemoryInstanceIndex.make[String]
        timeouts <- InMemoryTimeoutStore.make[String]
        _        <- FSMRuntime
          .delete[String, Leaf, Signal]("missing")
          .provide(
            env(
              events,
              index,
              DurableTimeoutStrategy.make(timeouts),
              OptimisticLockingStrategy.make[String],
            )
          )
        seq <- events.highestSequenceNr("missing")
      yield assertTrue(seq == 0L)
    },
    test("fiber timeouts and optimistic locking still drop the log") {
      for
        events <- InMemoryEventStore.make[String, Leaf, Signal]()
        index  <- InMemoryInstanceIndex.make[String]
        fiber  <- FiberTimeoutStrategy.make[String]
        _      <- events.append("a", Signal.Go, 0L)
        _      <- ZIO
          .scoped {
            FSMRuntime("a", machine, Leaf.Open, AliasExtractor.none[Leaf], IndexExtractor.none[Leaf])
              .flatMap(_.delete)
          }
          .provide(
            env(
              events,
              index,
              fiber,
              OptimisticLockingStrategy.make[String],
            )
          )
        seq <- events.highestSequenceNr("a")
      yield assertTrue(seq == 0L)
    },
    test("LockedFSMRuntime.delete drops the lock the wrapper holds") {
      for
        events <- InMemoryEventStore.make[String, Leaf, Signal]()
        index  <- InMemoryInstanceIndex.make[String]
        lock   <- InMemoryFSMInstanceLock.make[String]
        fiber  <- FiberTimeoutStrategy.make[String]
        now    <- Clock.instant
        _      <- lock.tryAcquire("a", "other", 30.seconds, now)
        _      <- ZIO
          .scoped {
            FSMRuntime("a", machine, Leaf.Open, AliasExtractor.none[Leaf], IndexExtractor.none[Leaf])
              .flatMap(fsm => LockedFSMRuntime(fsm, lock).flatMap(_.delete))
          }
          .provide(
            env(
              events,
              index,
              fiber,
              OptimisticLockingStrategy.make[String],
            )
          )
        held <- lock.get("a", now)
      yield assertTrue(held.isEmpty)
    },
  ) @@ TestAspect.timeout(10.seconds)
end DeleteInstanceSpec
