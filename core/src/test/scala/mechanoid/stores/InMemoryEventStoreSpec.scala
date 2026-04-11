package mechanoid.stores

import zio.*
import zio.test.*
import mechanoid.persistence.{EventStore, FSMSnapshot}

object InMemoryEventStoreSpec extends ZIOSpecDefault:

  // Simple test types
  sealed trait TestState
  case object StateA extends TestState
  case object StateB extends TestState

  sealed trait TestEvent
  case object Event1 extends TestEvent
  case object Event2 extends TestEvent

  def spec = suite("InMemoryEventStore")(
    suite("make")(
      test("creates a bounded store with default config") {
        for store <- InMemoryEventStore.make[String, TestState, TestEvent]()
        yield assertTrue(store != null)
      },
      test("creates a bounded store with custom limit") {
        for store <- InMemoryEventStore.make[String, TestState, TestEvent](maxEventsPerInstance = 5)
        yield assertTrue(store != null)
      },
    ),
    suite("makeUnbounded")(
      test("creates an unbounded store") {
        for store <- InMemoryEventStore.makeUnbounded[String, TestState, TestEvent]
        yield assertTrue(store != null)
      }
    ),
    suite("append")(
      test("appends event and returns sequence number") {
        for
          store <- InMemoryEventStore.make[String, TestState, TestEvent]()
          seq1  <- store.append("fsm-1", Event1, 0L)
          seq2  <- store.append("fsm-1", Event2, 1L)
        yield assertTrue(seq1 == 1L, seq2 == 2L)
      },
      test("maintains separate sequence numbers per instance") {
        for
          store <- InMemoryEventStore.make[String, TestState, TestEvent]()
          seq1a <- store.append("fsm-1", Event1, 0L)
          seq2a <- store.append("fsm-2", Event1, 0L)
          seq1b <- store.append("fsm-1", Event2, 1L)
        yield assertTrue(seq1a == 1L, seq2a == 1L, seq1b == 2L)
      },
    ),
    suite("loadEvents")(
      test("loads events for an instance") {
        for
          store  <- InMemoryEventStore.make[String, TestState, TestEvent]()
          _      <- store.append("fsm-1", Event1, 0L)
          _      <- store.append("fsm-1", Event2, 1L)
          events <- store.loadEvents("fsm-1").runCollect
        yield assertTrue(
          events.size == 2,
          events(0).event == Event1,
          events(1).event == Event2,
        )
      },
      test("returns empty stream for non-existent instance") {
        for
          store  <- InMemoryEventStore.make[String, TestState, TestEvent]()
          events <- store.loadEvents("non-existent").runCollect
        yield assertTrue(events.isEmpty)
      },
      test("does not load events from other instances") {
        for
          store  <- InMemoryEventStore.make[String, TestState, TestEvent]()
          _      <- store.append("fsm-1", Event1, 0L)
          _      <- store.append("fsm-2", Event2, 0L)
          events <- store.loadEvents("fsm-1").runCollect
        yield assertTrue(
          events.size == 1,
          events(0).event == Event1,
        )
      },
    ),
    suite("bounded eviction")(
      test("evicts oldest events when limit exceeded") {
        for
          store  <- InMemoryEventStore.make[String, TestState, TestEvent](maxEventsPerInstance = 2)
          _      <- store.append("fsm-1", Event1, 0L) // seqNr 1
          _      <- store.append("fsm-1", Event2, 1L) // seqNr 2
          _      <- store.append("fsm-1", Event1, 2L) // seqNr 3, evicts seqNr 1
          events <- store.loadEvents("fsm-1").runCollect
        yield assertTrue(
          events.size == 2,
          events(0).sequenceNr == 2L,
          events(1).sequenceNr == 3L,
        )
      },
      test("maintains sequence numbers after eviction") {
        for
          store <- InMemoryEventStore.make[String, TestState, TestEvent](maxEventsPerInstance = 2)
          _     <- store.append("fsm-1", Event1, 0L)
          _     <- store.append("fsm-1", Event2, 1L)
          _     <- store.append("fsm-1", Event1, 2L)
          seqNr <- store.highestSequenceNr("fsm-1")
        yield assertTrue(seqNr == 3L)
      },
    ),
    suite("unbounded")(
      test("keeps all events without eviction") {
        for
          store  <- InMemoryEventStore.makeUnbounded[String, TestState, TestEvent]
          _      <- ZIO.foreachDiscard(1 to 10)(_ => store.append("fsm-1", Event1, 0L))
          events <- store.loadEvents("fsm-1").runCollect
        yield assertTrue(events.size == 10)
      }
    ),
    suite("snapshots")(
      test("saves and loads snapshot") {
        for
          store <- InMemoryEventStore.make[String, TestState, TestEvent]()
          snapshot: FSMSnapshot[String, TestState] = FSMSnapshot("fsm-1", StateA, 5L, java.time.Instant.now())
          _      <- store.saveSnapshot(snapshot)
          loaded <- store.loadSnapshot("fsm-1")
        yield assertTrue(
          loaded.isDefined,
          loaded.get.state == StateA,
          loaded.get.sequenceNr == 5L,
        )
      },
      test("returns None for non-existent snapshot") {
        for
          store  <- InMemoryEventStore.make[String, TestState, TestEvent]()
          loaded <- store.loadSnapshot("non-existent")
        yield assertTrue(loaded.isEmpty)
      },
      test("overwrites previous snapshot") {
        for
          store <- InMemoryEventStore.make[String, TestState, TestEvent]()
          snap1: FSMSnapshot[String, TestState] = FSMSnapshot("fsm-1", StateA, 5L, java.time.Instant.now())
          snap2: FSMSnapshot[String, TestState] = FSMSnapshot("fsm-1", StateB, 10L, java.time.Instant.now())
          _      <- store.saveSnapshot(snap1)
          _      <- store.saveSnapshot(snap2)
          loaded <- store.loadSnapshot("fsm-1")
        yield assertTrue(
          loaded.get.state == StateB,
          loaded.get.sequenceNr == 10L,
        )
      },
    ),
    suite("highestSequenceNr")(
      test("returns 0 for new instance") {
        for
          store <- InMemoryEventStore.make[String, TestState, TestEvent]()
          seqNr <- store.highestSequenceNr("new-instance")
        yield assertTrue(seqNr == 0L)
      },
      test("returns highest sequence number after appends") {
        for
          store <- InMemoryEventStore.make[String, TestState, TestEvent]()
          _     <- store.append("fsm-1", Event1, 0L)
          _     <- store.append("fsm-1", Event2, 1L)
          _     <- store.append("fsm-1", Event1, 2L)
          seqNr <- store.highestSequenceNr("fsm-1")
        yield assertTrue(seqNr == 3L)
      },
    ),
    suite("clear")(
      test("clears all data") {
        for
          store <- InMemoryEventStore.make[String, TestState, TestEvent]()
          _     <- store.append("fsm-1", Event1, 0L)
          snapshot: FSMSnapshot[String, TestState] = FSMSnapshot("fsm-1", StateA, 1L, java.time.Instant.now())
          _      <- store.saveSnapshot(snapshot)
          _      <- store.clear
          events <- store.loadEvents("fsm-1").runCollect
          snap   <- store.loadSnapshot("fsm-1")
          seqNr  <- store.highestSequenceNr("fsm-1")
        yield assertTrue(events.isEmpty, snap.isEmpty, seqNr == 0L)
      }
    ),
    suite("layer")(
      test("provides EventStore service with default config") {
        val program =
          for seqNr <- ZIO.serviceWithZIO[EventStore[String, TestState, TestEvent]](
              _.append("fsm-1", Event1, 0L)
            )
          yield assertTrue(seqNr == 1L)

        program.provide(InMemoryEventStore.layer[String, TestState, TestEvent])
      },
      test("provides EventStore service with custom config") {
        val program = for
          store  <- ZIO.service[EventStore[String, TestState, TestEvent]]
          _      <- store.append("fsm-1", Event1, 0L)
          _      <- store.append("fsm-1", Event2, 1L)
          _      <- store.append("fsm-1", Event1, 2L) // Should trigger eviction
          events <- store.loadEvents("fsm-1").runCollect
        yield assertTrue(events.size == 2) // Only 2 due to limit

        program.provide(
          InMemoryEventStore.layer[String, TestState, TestEvent](
            InMemoryEventStore.Config(maxEventsPerInstance = 2)
          )
        )
      },
      test("unboundedLayer provides unbounded store") {
        val program = for
          store  <- ZIO.service[EventStore[String, TestState, TestEvent]]
          _      <- ZIO.foreachDiscard(1 to 10)(_ => store.append("fsm-1", Event1, 0L))
          events <- store.loadEvents("fsm-1").runCollect
        yield assertTrue(events.size == 10)

        program.provide(InMemoryEventStore.unboundedLayer[String, TestState, TestEvent])
      },
    ),
    suite("EventStore trait default methods")(
      test("loadEventsFrom filters events after sequence number") {
        for
          store <- InMemoryEventStore.make[String, TestState, TestEvent]()
          _     <- store.append("fsm-1", Event1, 0L) // seqNr 1
          _     <- store.append("fsm-1", Event2, 1L) // seqNr 2
          _     <- store.append("fsm-1", Event1, 2L) // seqNr 3
          // loadEventsFrom with fromSequenceNr=1 should return events with seqNr > 1
          events <- store.loadEventsFrom("fsm-1", 1L).runCollect
        yield assertTrue(
          events.size == 2,
          events(0).sequenceNr == 2L,
          events(1).sequenceNr == 3L,
        )
      },
      test("loadEventsFrom returns empty when fromSequenceNr is at highest") {
        for
          store  <- InMemoryEventStore.make[String, TestState, TestEvent]()
          _      <- store.append("fsm-1", Event1, 0L) // seqNr 1
          _      <- store.append("fsm-1", Event2, 1L) // seqNr 2
          events <- store.loadEventsFrom("fsm-1", 2L).runCollect
        yield assertTrue(events.isEmpty)
      },
      test("loadEventsFrom returns all events when fromSequenceNr is 0") {
        for
          store  <- InMemoryEventStore.make[String, TestState, TestEvent]()
          _      <- store.append("fsm-1", Event1, 0L) // seqNr 1
          _      <- store.append("fsm-1", Event2, 1L) // seqNr 2
          events <- store.loadEventsFrom("fsm-1", 0L).runCollect
        yield assertTrue(events.size == 2)
      },
      test("deleteEventsTo default implementation is no-op") {
        for
          store <- InMemoryEventStore.make[String, TestState, TestEvent]()
          _     <- store.append("fsm-1", Event1, 0L)
          _     <- store.append("fsm-1", Event2, 1L)
          // Call deleteEventsTo - default impl does nothing
          _ <- store.deleteEventsTo("fsm-1", 1L)
          // Events should still be there
          events <- store.loadEvents("fsm-1").runCollect
        yield assertTrue(events.size == 2)
      },
      test("currentState returns state from latest snapshot") {
        for
          store <- InMemoryEventStore.make[String, TestState, TestEvent]()
          // No snapshot yet
          state1 <- store.currentState("fsm-1")
          // Save a snapshot
          snapshot: FSMSnapshot[String, TestState] = FSMSnapshot("fsm-1", StateA, 5L, java.time.Instant.now())
          _      <- store.saveSnapshot(snapshot)
          state2 <- store.currentState("fsm-1")
        yield assertTrue(
          state1.isEmpty,
          state2.contains(StateA),
        )
      },
      test("currentState returns None for non-existent instance") {
        for
          store <- InMemoryEventStore.make[String, TestState, TestEvent]()
          state <- store.currentState("non-existent")
        yield assertTrue(state.isEmpty)
      },
    ),
  ) @@ TestAspect.timeout(10.seconds)

end InMemoryEventStoreSpec
