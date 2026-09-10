package mechanoid.persistence.postgres

import zio.*
import zio.test.*
import mechanoid.PostgresTestContainer
import mechanoid.core.*
import mechanoid.postgres.finiteJsonCodec
import mechanoid.persistence.*

object PostgresEventStoreSpec extends ZIOSpecDefault:

  // Test state and event types - Finite auto-derives JsonCodec
  enum TestState derives Finite:
    case Initial
    case Processing
    case Completed
    case Failed

  enum TestEvent derives Finite:
    case Started(id: String)
    case Processed(result: String)
    case Finished
    case Error(message: String)
    case Timeout // User-defined timeout event

  val xaLayer    = PostgresTestContainer.DataSourceProvider.transactor
  val storeLayer = xaLayer >>> PostgresEventStore.makeLayer[TestState, TestEvent]

  private def uniqueId(prefix: String): String = s"$prefix-${java.util.UUID.randomUUID()}"

  private def classify(results: Iterable[Either[MechanoidError, Long]]) =
    (
      results.collect { case Right(n) => n }.toList,
      results.collect { case Left(e: SequenceConflictError) => e }.toList,
      results.collect { case Left(e) if !e.isInstanceOf[SequenceConflictError] => e }.toList,
    )

  def spec = suite("PostgresEventStore")(
    test("append persists an event with correct sequence number") {
      for
        store <- ZIO.service[EventStore[String, TestState, TestEvent]]
        // Pass expected current seq (0 for first event), get back new seq (1)
        seqNr <- store.append("event-test-1", TestEvent.Started("123"), 0)
      yield assertTrue(seqNr == 1L)
    },
    test("append increments sequence numbers") {
      for
        store <- ZIO.service[EventStore[String, TestState, TestEvent]]
        // Each append passes expected current and gets back new seq
        seq1 <- store.append("event-test-2", TestEvent.Started("abc"), 0)
        seq2 <- store.append("event-test-2", TestEvent.Processed("ok"), 1)
        seq3 <- store.append("event-test-2", TestEvent.Finished, 2)
      yield assertTrue(seq1 == 1L, seq2 == 2L, seq3 == 3L)
    },
    test("append fails on sequence conflict") {
      for
        store <- ZIO.service[EventStore[String, TestState, TestEvent]]
        _     <- store.append("event-test-3", TestEvent.Started("x"), 0)
        // Try to append expecting seq 0, but it's now 1
        result <- store.append("event-test-3", TestEvent.Processed("y"), 0).either
      yield result match
        case Left(e: SequenceConflictError) => assertTrue(e.expectedSeqNr == 0L, e.actualSeqNr == 1L)
        case _                              => assertTrue(false)
    },
    test("append handles Timeout events") {
      for
        store  <- ZIO.service[EventStore[String, TestState, TestEvent]]
        seqNr  <- store.append("event-test-4", TestEvent.Timeout, 0)
        events <- store.loadEvents("event-test-4").runCollect
      yield assertTrue(
        seqNr == 1L,
        events.head.event == TestEvent.Timeout,
      )
    },
    test("loadEvents returns events in sequence order") {
      for
        store  <- ZIO.service[EventStore[String, TestState, TestEvent]]
        _      <- store.append("event-test-5", TestEvent.Started("order-test"), 0)
        _      <- store.append("event-test-5", TestEvent.Processed("step-1"), 1)
        _      <- store.append("event-test-5", TestEvent.Processed("step-2"), 2)
        _      <- store.append("event-test-5", TestEvent.Finished, 3)
        events <- store.loadEvents("event-test-5").runCollect
      yield assertTrue(
        events.length == 4,
        events.map(_.sequenceNr) == Chunk(1L, 2L, 3L, 4L),
      )
    },
    test("loadEventsFrom returns events after sequence number") {
      for
        store  <- ZIO.service[EventStore[String, TestState, TestEvent]]
        _      <- store.append("event-test-6", TestEvent.Started("from-test"), 0)
        _      <- store.append("event-test-6", TestEvent.Processed("a"), 1)
        _      <- store.append("event-test-6", TestEvent.Processed("b"), 2)
        events <- store.loadEventsFrom("event-test-6", 1).runCollect
      yield assertTrue(
        events.length == 2,
        events.map(_.sequenceNr) == Chunk(2L, 3L),
      )
    },
    test("saveSnapshot and loadSnapshot roundtrip") {
      for
        store <- ZIO.service[EventStore[String, TestState, TestEvent]]
        now   <- Clock.instant
        snapshot = FSMSnapshot("snapshot-test-1", TestState.Processing, 5L, now)
        _      <- store.saveSnapshot(snapshot)
        loaded <- store.loadSnapshot("snapshot-test-1")
      yield loaded match
        case Some(s) =>
          assertTrue(
            s.instanceId == "snapshot-test-1",
            s.state == TestState.Processing,
            s.sequenceNr == 5L,
          )
        case None => assertTrue(false)
    },
    test("saveSnapshot replaces existing snapshot (upsert)") {
      for
        store  <- ZIO.service[EventStore[String, TestState, TestEvent]]
        now    <- Clock.instant
        _      <- store.saveSnapshot(FSMSnapshot("snapshot-test-2", TestState.Initial, 1L, now))
        _      <- store.saveSnapshot(FSMSnapshot("snapshot-test-2", TestState.Completed, 10L, now))
        loaded <- store.loadSnapshot("snapshot-test-2")
      yield loaded match
        case Some(s) => assertTrue(s.state == TestState.Completed, s.sequenceNr == 10L)
        case None    => assertTrue(false)
    },
    test("loadSnapshot returns None for nonexistent instance") {
      for
        store  <- ZIO.service[EventStore[String, TestState, TestEvent]]
        loaded <- store.loadSnapshot("does-not-exist")
      yield assertTrue(loaded.isEmpty)
    },
    test("deleteEventsTo removes old events") {
      for
        store  <- ZIO.service[EventStore[String, TestState, TestEvent]]
        _      <- store.append("delete-test-1", TestEvent.Started("d"), 0)
        _      <- store.append("delete-test-1", TestEvent.Processed("e"), 1)
        _      <- store.append("delete-test-1", TestEvent.Finished, 2)
        _      <- store.deleteEventsTo("delete-test-1", 2)
        events <- store.loadEvents("delete-test-1").runCollect
      yield assertTrue(
        events.length == 1,
        events.head.sequenceNr == 3L,
      )
    },
    test("highestSequenceNr returns correct value") {
      for
        store   <- ZIO.service[EventStore[String, TestState, TestEvent]]
        _       <- store.append("highest-test-1", TestEvent.Started("h"), 0)
        _       <- store.append("highest-test-1", TestEvent.Processed("i"), 1)
        highest <- store.highestSequenceNr("highest-test-1")
      yield assertTrue(highest == 2L)
    },
    test("highestSequenceNr returns 0 for new instance") {
      for
        store   <- ZIO.service[EventStore[String, TestState, TestEvent]]
        highest <- store.highestSequenceNr("new-instance")
      yield assertTrue(highest == 0L)
    },
    suite("append properties")(
      test("concurrent first append: one winner, contiguous log, real actualSeqNr") {
        check(Gen.int(2, 16)) { writers =>
          for
            store <- ZIO.service[EventStore[String, TestState, TestEvent]]
            instanceId = uniqueId("prop-first")
            results <- ZIO.foreachPar(1 to writers) { i =>
              store.append(instanceId, TestEvent.Started(s"$i"), 0).either
            }
            (successes, conflicts, other) = classify(results)
            events  <- store.loadEvents(instanceId).runCollect
            highest <- store.highestSequenceNr(instanceId)
          yield assertTrue(
            successes == List(1L),
            conflicts.length == writers - 1,
            other.isEmpty,
            events.map(_.sequenceNr) == Chunk(1L),
            highest == 1L,
            conflicts.forall(_.expectedSeqNr == 0L),
            conflicts.forall(_.actualSeqNr == 1L),
            conflicts.forall(_.instanceId == instanceId),
          )
        }
      },
      test("stale writers after a prefix all conflict with actual = prefix") {
        check(Gen.int(1, 8), Gen.int(2, 12)) { (prefix, stale) =>
          for
            store <- ZIO.service[EventStore[String, TestState, TestEvent]]
            instanceId = uniqueId("prop-stale")
            _ <- ZIO.foreach(1 to prefix) { i =>
              store.append(instanceId, TestEvent.Processed(s"$i"), (i - 1).toLong)
            }
            results <- ZIO.foreachPar(1 to stale) { i =>
              store.append(instanceId, TestEvent.Started(s"stale-$i"), 0).either
            }
            (successes, conflicts, other) = classify(results)
            events  <- store.loadEvents(instanceId).runCollect
            highest <- store.highestSequenceNr(instanceId)
          yield assertTrue(
            successes.isEmpty,
            conflicts.length == stale,
            other.isEmpty,
            events.map(_.sequenceNr) == Chunk.fromIterable(1L to prefix.toLong),
            highest == prefix.toLong,
            conflicts.forall(_.expectedSeqNr == 0L),
            conflicts.forall(_.actualSeqNr == prefix.toLong),
          )
        }
      },
      test("concurrent appends at the current head extend the log by one") {
        check(Gen.int(0, 6), Gen.int(2, 12)) { (prefix, writers) =>
          for
            store <- ZIO.service[EventStore[String, TestState, TestEvent]]
            instanceId = uniqueId("prop-head")
            _ <- ZIO.foreach(1 to prefix) { i =>
              store.append(instanceId, TestEvent.Processed(s"$i"), (i - 1).toLong)
            }
            results <- ZIO.foreachPar(1 to writers) { i =>
              store.append(instanceId, TestEvent.Started(s"race-$i"), prefix.toLong).either
            }
            (successes, conflicts, other) = classify(results)
            events  <- store.loadEvents(instanceId).runCollect
            highest <- store.highestSequenceNr(instanceId)
            expected = (prefix + 1).toLong
          yield assertTrue(
            successes == List(expected),
            conflicts.length == writers - 1,
            other.isEmpty,
            events.map(_.sequenceNr) == Chunk.fromIterable(1L to expected),
            highest == expected,
            conflicts.forall(_.expectedSeqNr == prefix.toLong),
            conflicts.forall(_.actualSeqNr == expected),
          )
        }
      },
      test("sequential appends produce 1..n with no gaps") {
        check(Gen.int(1, 20)) { n =>
          for
            store <- ZIO.service[EventStore[String, TestState, TestEvent]]
            instanceId = uniqueId("prop-seq")
            seqNrs <- ZIO.foreach(1 to n) { i =>
              store.append(instanceId, TestEvent.Processed(s"$i"), (i - 1).toLong)
            }
            events  <- store.loadEvents(instanceId).runCollect
            highest <- store.highestSequenceNr(instanceId)
          yield assertTrue(
            seqNrs == (1L to n.toLong).toList,
            events.map(_.sequenceNr) == Chunk.fromIterable(1L to n.toLong),
            highest == n.toLong,
          )
        }
      },
    ) @@ TestAspect.samples(25),
  ).provideShared(storeLayer) @@ TestAspect.sequential
end PostgresEventStoreSpec
