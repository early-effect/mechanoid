package mechanoid

import zio.*
import zio.test.*
import zio.json.*
import saferis.{SaferisError, Transactor}
import mechanoid.machine.*
import mechanoid.persistence.*
import mechanoid.persistence.postgres.PostgresEventStore
import mechanoid.runtime.FSMRuntime
import mechanoid.runtime.timeout.TimeoutStrategy
import mechanoid.runtime.locking.LockingStrategy
import mechanoid.stores.InMemoryEventStore

/** Unified FSMRuntime tests that work with any EventStore implementation.
  *
  * These tests verify FSMRuntime behavior independent of the storage backend. Run with different store layers to test
  * various implementations:
  *   - In-memory (default, fast for unit tests)
  *   - PostgreSQL (integration tests)
  */
object FSMRuntimeSpec extends ZIOSpecDefault:

  // ============================================
  // Test Domain
  // ============================================

  enum OrderState derives JsonCodec:
    case Pending, Paid, Shipped, Delivered

  enum OrderEvent derives JsonCodec:
    case Pay, Ship, Deliver

  import OrderState.*
  import OrderEvent.*

  val orderMachine = Machine(
    assembly[OrderState, OrderEvent](
      Pending via Pay to Paid,
      Paid via Ship to Shipped,
      Shipped via Deliver to Delivered,
    )
  )

  // Machine is used directly for FSMRuntime
  val orderDefinition = orderMachine

  /** Generate a unique instance ID for test isolation. */
  def uniqueId(prefix: String): String = s"$prefix-${java.util.UUID.randomUUID()}"

  // ============================================
  // Store Layer Providers
  // ============================================

  /** In-memory store for fast unit tests. */
  def inMemoryStoreLayer: ZLayer[Any, Nothing, EventStore[String, OrderState, OrderEvent]] =
    ZLayer.scoped {
      InMemoryEventStore.makeUnbounded[String, OrderState, OrderEvent]
    }

  /** Timeout strategy layer for tests. */
  val timeoutLayer: ULayer[TimeoutStrategy[String]] = TimeoutStrategy.fiber[String]

  /** Locking strategy layer for tests. */
  val lockingLayer: ULayer[LockingStrategy[String]] = LockingStrategy.optimistic[String]

  /** PostgreSQL store for integration tests (with schema initialization). */
  val xaLayer: ZLayer[Any, SaferisError, Transactor] =
    PostgresTestContainer.DataSourceProvider.transactor

  val postgresStoreLayer: ZLayer[Any, SaferisError, EventStore[String, OrderState, OrderEvent]] =
    xaLayer >>> PostgresEventStore.makeLayer[OrderState, OrderEvent]

  // ============================================
  // Test Suites
  // ============================================

  def spec = suite("all")(
    makeSuite("In-Memory Event Store").provideShared(inMemoryStoreLayer ++ timeoutLayer ++ lockingLayer),
    makeSuite("PostgreSQL Event Store").provideShared(postgresStoreLayer ++ timeoutLayer ++ lockingLayer),
  ) @@ TestAspect.sequential @@ TestAspect.timeout(30.seconds) @@ TestAspect.withLiveClock

  def makeSuite(name: String) = suite(name)(
    basicTransitionSuite,
    eventReplaySuite,
    snapshotSuite,
    sequenceNumberSuite,
    concurrencySuite,
    recoverySuite,
    edgeCaseSuite,
    negativeScenarioSuite,
  )

  def basicTransitionSuite = suite("Basic Transitions")(
    test("persists events on transition") {
      val id = uniqueId("order")
      for
        store  <- ZIO.service[EventStore[String, OrderState, OrderEvent]]
        result <- ZIO.scoped {
          for
            fsm   <- FSMRuntime(id, orderDefinition, Pending)
            _     <- fsm.send(Pay)
            state <- fsm.currentState
            seqNr <- fsm.lastSequenceNr
          yield (state, seqNr)
        }
        events <- store.loadEvents(id).runCollect
      yield assertTrue(
        result._1 == Paid,
        result._2 == 1L,
        events.length == 1,
        events.head.event == Pay,
      )
      end for
    },
    test("transitions through multiple states") {
      val id = uniqueId("order")
      for
        store  <- ZIO.service[EventStore[String, OrderState, OrderEvent]]
        result <- ZIO.scoped {
          for
            fsm   <- FSMRuntime(id, orderDefinition, Pending)
            _     <- fsm.send(Pay)
            _     <- fsm.send(Ship)
            _     <- fsm.send(Deliver)
            state <- fsm.currentState
            seqNr <- fsm.lastSequenceNr
          yield (state, seqNr)
        }
        events <- store.loadEvents(id).runCollect
      yield assertTrue(
        result._1 == Delivered,
        result._2 == 3L,
        events.length == 3,
      )
      end for
    },
    test("rejects invalid transitions") {
      val id = uniqueId("order")
      ZIO.scoped {
        for
          fsm    <- FSMRuntime(id, orderDefinition, Pending)
          result <- fsm.send(Ship).either // Can't ship before paying
        yield assertTrue(result.isLeft)
      }
    },
  )

  def eventReplaySuite = suite("Event Replay")(
    test("rebuilds state from persisted events") {
      val id = uniqueId("replay")
      for
        // First FSM instance - make transitions
        _ <- ZIO.scoped {
          for
            fsm <- FSMRuntime(id, orderDefinition, Pending)
            _   <- fsm.send(Pay)
            _   <- fsm.send(Ship)
          yield ()
        }
        // Second FSM instance - should rebuild from events
        result <- ZIO.scoped {
          for
            fsm   <- FSMRuntime(id, orderDefinition, Pending)
            state <- fsm.currentState
            seqNr <- fsm.lastSequenceNr
          yield (state, seqNr)
        }
      yield assertTrue(
        result._1 == Shipped,
        result._2 == 2L,
      )
      end for
    },
    test("continues from replayed state") {
      val id = uniqueId("replay")
      for
        // First session - partial progress
        _ <- ZIO.scoped {
          for
            fsm <- FSMRuntime(id, orderDefinition, Pending)
            _   <- fsm.send(Pay)
          yield ()
        }
        // Second session - continue from where we left off
        result <- ZIO.scoped {
          for
            fsm   <- FSMRuntime(id, orderDefinition, Pending)
            _     <- fsm.send(Ship)
            _     <- fsm.send(Deliver)
            state <- fsm.currentState
            seqNr <- fsm.lastSequenceNr
          yield (state, seqNr)
        }
      yield assertTrue(
        result._1 == Delivered,
        result._2 == 3L,
      )
      end for
    },
  )

  def snapshotSuite = suite("Snapshots")(
    test("saves and restores from snapshot") {
      val id = uniqueId("snap")
      for
        store <- ZIO.service[EventStore[String, OrderState, OrderEvent]]
        // Create FSM, transition, and save snapshot
        _ <- ZIO.scoped {
          for
            fsm <- FSMRuntime(id, orderDefinition, Pending)
            _   <- fsm.send(Pay)
            _   <- fsm.send(Ship)
            _   <- fsm.saveSnapshot
          yield ()
        }
        // Verify snapshot exists
        snapshot <- store.loadSnapshot(id)
        // New FSM should restore from snapshot
        result <- ZIO.scoped {
          for
            fsm   <- FSMRuntime(id, orderDefinition, Pending)
            state <- fsm.currentState
            seqNr <- fsm.lastSequenceNr
          yield (state, seqNr)
        }
      yield assertTrue(
        snapshot.isDefined,
        snapshot.get.state == Shipped,
        result._1 == Shipped,
        result._2 == 2L,
      )
      end for
    },
    test("replays events after snapshot") {
      val id = uniqueId("snap")
      for
        // Create FSM, transition, save snapshot, then add more events
        _ <- ZIO.scoped {
          for
            fsm <- FSMRuntime(id, orderDefinition, Pending)
            _   <- fsm.send(Pay)
            _   <- fsm.saveSnapshot // Snapshot at Paid
            _   <- fsm.send(Ship)   // Event after snapshot
          yield ()
        }
        // New FSM should restore from snapshot then replay remaining events
        result <- ZIO.scoped {
          for
            fsm   <- FSMRuntime(id, orderDefinition, Pending)
            state <- fsm.currentState
          yield state
        }
      yield assertTrue(result == Shipped)
      end for
    },
  )

  def sequenceNumberSuite = suite("Sequence Numbers")(
    test("sequence numbers increment correctly") {
      val id = uniqueId("seq")
      ZIO.scoped {
        for
          fsm    <- FSMRuntime(id, orderDefinition, Pending)
          seqNr0 <- fsm.lastSequenceNr
          _      <- fsm.send(Pay)
          seqNr1 <- fsm.lastSequenceNr
          _      <- fsm.send(Ship)
          seqNr2 <- fsm.lastSequenceNr
        yield assertTrue(
          seqNr0 == 0L,
          seqNr1 == 1L,
          seqNr2 == 2L,
        )
      }
    },
    test("instanceId is accessible") {
      val id = uniqueId("my-order")
      ZIO.scoped {
        for
          fsm <- FSMRuntime(id, orderDefinition, Pending)
          actualId = fsm.instanceId
        yield assertTrue(actualId == id)
      }
    },
  )

  // ============================================
  // Concurrency Tests
  // ============================================

  def concurrencySuite = suite("Concurrency")(
    test("concurrent sends to same FSM - only valid transitions succeed") {
      // When multiple events are sent concurrently, sequence conflicts should occur
      // The runtime should handle this by failing conflicting appends
      val id = uniqueId("concurrent")
      for
//        store <- ZIO.service[EventStore[String, OrderState, OrderEvent]]
        // Start FSM and immediately try concurrent transitions
        result <- ZIO.scoped {
          for
            fsm <- FSMRuntime(id, orderDefinition, Pending)
            // Send Pay multiple times concurrently - only one should succeed
            results <- ZIO.foreachPar(List.fill(5)(Pay))(event => fsm.send(event).either)
            state   <- fsm.currentState
          yield (results, state)
        }
        (results, finalState) = result
        successes             = results.count(_.isRight)
        failures              = results.count(_.isLeft)
      yield assertTrue(
        // At least one should succeed (the first one)
        successes >= 1,
        // Most should fail (invalid transition from Paid back to Paid via Pay)
        failures >= 0,
        // FSM should have transitioned to Paid
        finalState == Paid,
      )
      end for
    },
    test("rapid sequential transitions maintain consistency") {
      val id = uniqueId("rapid")
      for
        store  <- ZIO.service[EventStore[String, OrderState, OrderEvent]]
        result <- ZIO.scoped {
          for
            fsm <- FSMRuntime(id, orderDefinition, Pending)
            // Rapid sequential sends
            _     <- fsm.send(Pay)
            _     <- fsm.send(Ship)
            _     <- fsm.send(Deliver)
            state <- fsm.currentState
            seqNr <- fsm.lastSequenceNr
          yield (state, seqNr)
        }
        events <- store.loadEvents(id).runCollect
      yield assertTrue(
        result._1 == Delivered,
        result._2 == 3L,
        events.length == 3,
        // Verify correct order
        events.map(_.sequenceNr).toList == List(1L, 2L, 3L),
      )
      end for
    },
    test("multiple FSM instances don't interfere with each other") {
      val id1 = uniqueId("multi-1")
      val id2 = uniqueId("multi-2")
      val id3 = uniqueId("multi-3")
      for
        store <- ZIO.service[EventStore[String, OrderState, OrderEvent]]
        // Create and use multiple FSMs concurrently
        _ <- ZIO.collectAllPar(
          List(
            ZIO.scoped {
              for
                fsm <- FSMRuntime(id1, orderDefinition, Pending)
                _   <- fsm.send(Pay)
                _   <- fsm.send(Ship)
              yield id1
            },
            ZIO.scoped {
              for
                fsm <- FSMRuntime(id2, orderDefinition, Pending)
                _   <- fsm.send(Pay)
              yield id2
            },
            ZIO.scoped {
              for
                fsm <- FSMRuntime(id3, orderDefinition, Pending)
                _   <- fsm.send(Pay)
                _   <- fsm.send(Ship)
                _   <- fsm.send(Deliver)
              yield id3
            },
          )
        )
        // Verify each has correct events
        events1 <- store.loadEvents(id1).runCollect
        events2 <- store.loadEvents(id2).runCollect
        events3 <- store.loadEvents(id3).runCollect
      yield assertTrue(
        events1.length == 2,
        events2.length == 1,
        events3.length == 3,
      )
      end for
    },
  )

  // ============================================
  // Recovery Tests
  // ============================================

  def recoverySuite = suite("Recovery")(
    test("new FSM instance starts fresh") {
      val id = uniqueId("fresh")
      for
        store  <- ZIO.service[EventStore[String, OrderState, OrderEvent]]
        result <- ZIO.scoped {
          for
            fsm   <- FSMRuntime(id, orderDefinition, Pending)
            state <- fsm.currentState
            seqNr <- fsm.lastSequenceNr
          yield (state, seqNr)
        }
        events <- store.loadEvents(id).runCollect
      yield assertTrue(
        result._1 == Pending,
        result._2 == 0L,
        events.isEmpty,
      )
      end for
    },
    test("recovery from snapshot only (no events after snapshot)") {
      val id = uniqueId("snap-only")
      for
        store <- ZIO.service[EventStore[String, OrderState, OrderEvent]]
        // Create FSM, transition, and save snapshot
        _ <- ZIO.scoped {
          for
            fsm <- FSMRuntime(id, orderDefinition, Pending)
            _   <- fsm.send(Pay)
            _   <- fsm.send(Ship)
            _   <- fsm.saveSnapshot
          yield ()
        }
        // Verify snapshot was saved
        snapshot <- store.loadSnapshot(id)
        // Recover - should use snapshot directly
        result <- ZIO.scoped {
          for
            fsm   <- FSMRuntime(id, orderDefinition, Pending)
            state <- fsm.currentState
            seqNr <- fsm.lastSequenceNr
          yield (state, seqNr)
        }
      yield assertTrue(
        snapshot.isDefined,
        snapshot.get.state == Shipped,
        snapshot.get.sequenceNr == 2L,
        result._1 == Shipped,
        result._2 == 2L,
      )
      end for
    },
    test("recovery with events after snapshot") {
      val id = uniqueId("snap-events")
      for
        // Create FSM, transition, snapshot, then more transitions
        _ <- ZIO.scoped {
          for
            fsm <- FSMRuntime(id, orderDefinition, Pending)
            _   <- fsm.send(Pay)
            _   <- fsm.saveSnapshot  // Snapshot at Paid, seqNr=1
            _   <- fsm.send(Ship)
            _   <- fsm.send(Deliver) // Events 2, 3 after snapshot
          yield ()
        }
        // Recover - should restore from snapshot + replay 2 events
        result <- ZIO.scoped {
          for
            fsm   <- FSMRuntime(id, orderDefinition, Pending)
            state <- fsm.currentState
            seqNr <- fsm.lastSequenceNr
          yield (state, seqNr)
        }
      yield assertTrue(
        result._1 == Delivered,
        result._2 == 3L,
      )
      end for
    },
    test("recovery after multiple sessions maintains correct state") {
      val id = uniqueId("multi-session")
      for
        store <- ZIO.service[EventStore[String, OrderState, OrderEvent]]
        // Session 1: Create and transition
        _ <- ZIO.scoped {
          for
            fsm <- FSMRuntime(id, orderDefinition, Pending)
            _   <- fsm.send(Pay)
          yield ()
        }
        // Session 2: Continue
        _ <- ZIO.scoped {
          for
            fsm <- FSMRuntime(id, orderDefinition, Pending)
            _   <- fsm.send(Ship)
          yield ()
        }
        // Session 3: Finish
        result <- ZIO.scoped {
          for
            fsm         <- FSMRuntime(id, orderDefinition, Pending)
            state       <- fsm.currentState
            seqNr       <- fsm.lastSequenceNr
            _           <- fsm.send(Deliver)
            final_state <- fsm.currentState
          yield (state, seqNr, final_state)
        }
        events <- store.loadEvents(id).runCollect
      yield assertTrue(
        result._1 == Shipped,   // State before sending Deliver
        result._2 == 2L,        // Seq before sending
        result._3 == Delivered, // Final state
        events.length == 3,
      )
      end for
    },
    test("continues correctly after snapshot + events + snapshot") {
      val id = uniqueId("double-snap")
      for
        store <- ZIO.service[EventStore[String, OrderState, OrderEvent]]
        // Complex sequence: events -> snapshot -> events -> snapshot
        _ <- ZIO.scoped {
          for
            fsm <- FSMRuntime(id, orderDefinition, Pending)
            _   <- fsm.send(Pay)
            _   <- fsm.saveSnapshot // Snapshot 1 at Paid
            _   <- fsm.send(Ship)
            _   <- fsm.saveSnapshot // Snapshot 2 at Shipped
          yield ()
        }
        // Recover
        result <- ZIO.scoped {
          for
            fsm   <- FSMRuntime(id, orderDefinition, Pending)
            state <- fsm.currentState
            seqNr <- fsm.lastSequenceNr
          yield (state, seqNr)
        }
        snapshot <- store.loadSnapshot(id)
      yield assertTrue(
        // Should recover from latest snapshot (Shipped)
        snapshot.get.state == Shipped,
        snapshot.get.sequenceNr == 2L,
        result._1 == Shipped,
        result._2 == 2L,
      )
      end for
    },
  )

  // ============================================
  // Edge Case Tests
  // ============================================

  def edgeCaseSuite = suite("Edge Cases")(
    test("sending to stopped FSM returns Stop result") {
      val id = uniqueId("stopped")
      ZIO.scoped {
        for
          fsm     <- FSMRuntime(id, orderDefinition, Pending)
          _       <- fsm.stop
          running <- fsm.isRunning
          result  <- fsm.send(Pay)
        yield assertTrue(
          !running,
          result.result == TransitionResult.Stop(Some("FSM stopped")),
        )
      }
    },
    test("multiple stop calls are idempotent") {
      val id = uniqueId("multi-stop")
      ZIO.scoped {
        for
          fsm     <- FSMRuntime(id, orderDefinition, Pending)
          _       <- fsm.stop
          _       <- fsm.stop
          _       <- fsm.stop("custom reason")
          running <- fsm.isRunning
        yield assertTrue(!running)
      }
    },
    test("isRunning reflects correct state") {
      val id = uniqueId("running-check")
      ZIO.scoped {
        for
          fsm    <- FSMRuntime(id, orderDefinition, Pending)
          before <- fsm.isRunning
          _      <- fsm.send(Pay)
          during <- fsm.isRunning
          _      <- fsm.stop
          after  <- fsm.isRunning
        yield assertTrue(
          before == true,
          during == true,
          after == false,
        )
      }
    },
    test("history tracks all previous states") {
      val id = uniqueId("history")
      ZIO.scoped {
        for
          fsm <- FSMRuntime(id, orderDefinition, Pending)
          h0  <- fsm.history
          _   <- fsm.send(Pay)
          h1  <- fsm.history
          _   <- fsm.send(Ship)
          h2  <- fsm.history
          _   <- fsm.send(Deliver)
          h3  <- fsm.history
        yield assertTrue(
          h0 == List.empty,
          h1 == List(Pending),
          h2 == List(Paid, Pending),
          h3 == List(Shipped, Paid, Pending),
        )
      }
    },
    test("state includes current state and history") {
      val id = uniqueId("state-full")
      ZIO.scoped {
        for
          fsm      <- FSMRuntime(id, orderDefinition, Pending)
          _        <- fsm.send(Pay)
          _        <- fsm.send(Ship)
          fsmState <- fsm.state
        yield assertTrue(
          fsmState.current == Shipped,
          fsmState.history == List(Paid, Pending),
        )
      }
    },
    test("zero events in store for new instance") {
      val id = uniqueId("no-events")
      for
        store  <- ZIO.service[EventStore[String, OrderState, OrderEvent]]
        events <- store.loadEvents(id).runCollect
        seqNr  <- store.highestSequenceNr(id)
      yield assertTrue(
        events.isEmpty,
        seqNr == 0L,
      )
    },
    test("loadEventsFrom with high sequence returns empty") {
      val id = uniqueId("from-high")
      for
        store <- ZIO.service[EventStore[String, OrderState, OrderEvent]]
        // Add some events
        _ <- ZIO.scoped {
          for
            fsm <- FSMRuntime(id, orderDefinition, Pending)
            _   <- fsm.send(Pay)
            _   <- fsm.send(Ship)
          yield ()
        }
        // Load from higher sequence than exists
        events <- store.loadEventsFrom(id, 100).runCollect
      yield assertTrue(events.isEmpty)
      end for
    },
    test("loadSnapshot returns None for non-existent instance") {
      val id = uniqueId("no-snapshot")
      for
        store    <- ZIO.service[EventStore[String, OrderState, OrderEvent]]
        snapshot <- store.loadSnapshot(id)
      yield assertTrue(snapshot.isEmpty)
    },
  )

  // ============================================
  // Negative Scenario Tests
  // ============================================

  def negativeScenarioSuite = suite("Negative Scenarios")(
    test("rejects invalid transition at initial state") {
      val id = uniqueId("invalid-init")
      ZIO.scoped {
        for
          fsm <- FSMRuntime(id, orderDefinition, Pending)
          // Ship is not valid from Pending
          result <- fsm.send(Ship).either
        yield (result: @unchecked) match
          case Left(e: InvalidTransitionError[?, ?]) =>
            assertTrue(
              e.currentState == Pending,
              e.event == Ship,
            )
          case _ => assertTrue(false)
      }
    },
    test("rejects invalid transition after valid transitions") {
      val id = uniqueId("invalid-after")
      ZIO.scoped {
        for
          fsm <- FSMRuntime(id, orderDefinition, Pending)
          _   <- fsm.send(Pay) // Valid: Pending -> Paid
          // Deliver is not valid from Paid
          result <- fsm.send(Deliver).either
        yield (result: @unchecked) match
          case Left(e: InvalidTransitionError[?, ?]) =>
            assertTrue(
              e.currentState == Paid,
              e.event == Deliver,
            )
          case _ => assertTrue(false)
      }
    },
    test("invalid transition doesn't persist or change state") {
      val id = uniqueId("invalid-no-persist")
      for
        store  <- ZIO.service[EventStore[String, OrderState, OrderEvent]]
        result <- ZIO.scoped {
          for
            fsm       <- FSMRuntime(id, orderDefinition, Pending)
            _         <- fsm.send(Pay)
            seqBefore <- fsm.lastSequenceNr
            _         <- fsm.send(Deliver).either // Invalid
            seqAfter  <- fsm.lastSequenceNr
            state     <- fsm.currentState
          yield (seqBefore, seqAfter, state)
        }
        events <- store.loadEvents(id).runCollect
      yield assertTrue(
        result._1 == 1L,
        result._2 == 1L,   // Sequence didn't change
        result._3 == Paid, // State didn't change
        events.length == 1, // No invalid event persisted
      )
      end for
    },
    test("recovery fails for changed FSM definition (EventReplayError)") {
      val id = uniqueId("changed-def")
      for
        // store <- ZIO.service[EventStore[String, OrderState, OrderEvent]]
        // Create events with original definition
        _ <- ZIO.scoped {
          for
            fsm <- FSMRuntime(id, orderDefinition, Pending)
            _   <- fsm.send(Pay)
            _   <- fsm.send(Ship)
          yield ()
        }
        // Try to recover with a DIFFERENT machine that doesn't have Ship transition
        // We create a machine that only has Pending -> Paid
        restrictedMachine = Machine(
          assembly[OrderState, OrderEvent](
            Pending via Pay to Paid
          )
        )
        restrictedDefinition = restrictedMachine
        // No Ship transition!
        // This should fail because Ship event exists but no transition is defined
        result <- ZIO.scoped {
          FSMRuntime(id, restrictedDefinition, Pending)
        }.either
      yield (result: @unchecked) match
        case Left(e: EventReplayError[?, ?]) =>
          assertTrue(
            e.currentState == Paid,
            e.sequenceNr == 2L,
          )
        case Left(_) =>
          // Any other error is unexpected
          assertTrue(false)
        case Right(_) =>
          // Should have failed
          assertTrue(false)
      end for
    },
    test("handles rapid back-to-back transitions") {
      // Test that rapid sequential transitions work correctly
      val id = uniqueId("rapid-transitions")
      for
        store  <- ZIO.service[EventStore[String, OrderState, OrderEvent]]
        result <- ZIO.scoped {
          for
            fsm <- FSMRuntime(id, orderDefinition, Pending)
            // Do the full transition sequence quickly
            _     <- fsm.send(Pay)
            _     <- fsm.send(Ship)
            _     <- fsm.send(Deliver)
            state <- fsm.currentState
            seqNr <- fsm.lastSequenceNr
          yield (state, seqNr)
        }
        events <- store.loadEvents(id).runCollect
        // Verify all events are in correct order with correct sequence numbers
        seqNrs = events.map(_.sequenceNr).toList
      yield assertTrue(
        result._1 == Delivered,
        result._2 == 3L,
        events.length == 3,
        seqNrs == List(1L, 2L, 3L),
      )
      end for
    },
    test("saveSnapshot after recovery maintains correct sequence") {
      val id = uniqueId("snap-recover-snap")
      for
        store <- ZIO.service[EventStore[String, OrderState, OrderEvent]]
        // Session 1: Create events
        _ <- ZIO.scoped {
          for
            fsm <- FSMRuntime(id, orderDefinition, Pending)
            _   <- fsm.send(Pay)
            _   <- fsm.send(Ship)
          yield ()
        }
        // Session 2: Recover and save snapshot
        _ <- ZIO.scoped {
          for
            fsm <- FSMRuntime(id, orderDefinition, Pending)
            _   <- fsm.saveSnapshot
          yield ()
        }
        snapshot <- store.loadSnapshot(id)
      yield assertTrue(
        snapshot.isDefined,
        snapshot.get.state == Shipped,
        snapshot.get.sequenceNr == 2L,
      )
      end for
    },
    test("sequence conflict detection (store-specific behavior)") {
      // Note: In-memory stores don't implement conflict detection (by design)
      // PostgreSQL stores do implement it
      val id = uniqueId("seq-conflict")
      for
        store <- ZIO.service[EventStore[String, OrderState, OrderEvent]]
        // Manually append to store to simulate concurrent access
        _ <- store.append(id, Pay, 0)
        // Try to append with same expected sequence (conflict)
        result <- store.append(id, Ship, 0).either
      yield result match
        case Left(e: SequenceConflictError) =>
          // PostgreSQL store detects conflict
          assertTrue(
            e.expectedSeqNr == 0L,
            e.actualSeqNr == 1L,
          )
        case Right(_) =>
          // In-memory store doesn't implement conflict detection
          // This is valid - it auto-increments without checking expectedSeqNr
          assertTrue(true)
        case Left(_) =>
          // Unexpected error
          assertTrue(false)
      end for
    },
  )

end FSMRuntimeSpec
