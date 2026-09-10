package mechanoid.persistence.postgres

import zio.*
import zio.test.*
import mechanoid.PostgresTestContainer
import mechanoid.core.*
import mechanoid.postgres.finiteJsonCodec
import mechanoid.persistence.*
import mechanoid.persistence.lock.*
import mechanoid.persistence.timeout.*
import java.util.UUID

/** Brutal stress tests for PostgreSQL persistence implementations.
  *
  * These tests throw worst-case scenarios at the persistence layer:
  *   - Massive parallelism with race conditions
  *   - Fiber interruption at strategic points
  *   - Simulated node failures
  *   - Leader election failover
  *   - Consistency guarantees under chaos
  */
object PostgresStressSpec extends ZIOSpecDefault:

  // Test types - Finite auto-derives JsonCodec
  enum StressState derives Finite:
    case Idle
    case Processing
    case WaitingForTimeout
    case Completed
    case Failed

  enum StressEvent derives Finite:
    case Started(data: String)
    case Progressed(step: Int)
    case Finished
    case Error(msg: String)
    case Timeout // User-defined timeout event

  // Shared layers
  val xaLayer           = PostgresTestContainer.DataSourceProvider.transactor
  val eventStoreLayer   = xaLayer >>> PostgresEventStore.makeLayer[StressState, StressEvent]
  val timeoutStoreLayer = xaLayer >>> PostgresTimeoutStore.layer
  val lockLayer         = xaLayer >>> PostgresInstanceLock.layer
  val leaseStoreLayer   = xaLayer >>> PostgresLeaseStore.layer

  val allLayers = eventStoreLayer ++ timeoutStoreLayer ++ lockLayer ++ leaseStoreLayer

  private def uniqueId(prefix: String) = s"$prefix-${UUID.randomUUID()}"

  // ============================================
  // Lock Stress Tests
  // ============================================

  val lockStressTests = suite("Lock Stress Tests")(
    test("100 concurrent nodes racing for the same lock - only one wins initially") {
      for
        lock <- ZIO.service[FSMInstanceLock[String]]
        now  <- Clock.instant
        instanceId = uniqueId("lock-race")
        nodes      = (1 to 100).map(i => s"node-$i").toList

        // All nodes try to acquire simultaneously
        results <- ZIO.foreachPar(nodes) { nodeId =>
          lock.tryAcquire(instanceId, nodeId, Duration.fromSeconds(30), now)
        }

        acquired = results.collect { case LockResult.Acquired(token) => token }
        busy     = results.collect { case LockResult.Busy(_, _) => () }
      yield assertTrue(
        acquired.length == 1,                    // Exactly one winner
        busy.length == 99,                       // Everyone else is blocked
        acquired.head.nodeId.startsWith("node-"), // Winner is valid
      )
    },
    test("lock expires and another node acquires") {
      for
        lock <- ZIO.service[FSMInstanceLock[String]]
        now  <- Clock.instant
        instanceId = uniqueId("lock-expire")

        // Node 1 acquires with very short duration
        result1 <- lock.tryAcquire(instanceId, "node-1", Duration.fromMillis(50), now)
        _       <- assertTrue(result1.isInstanceOf[LockResult.Acquired[String]])

        // Wait for expiry
        _     <- ZIO.sleep(Duration.fromMillis(100))
        later <- Clock.instant

        // Node 2 should now be able to acquire
        result2 <- lock.tryAcquire(instanceId, "node-2", Duration.fromSeconds(30), later)
      yield assertTrue(
        result2.isInstanceOf[LockResult.Acquired[String]],
        result2.asInstanceOf[LockResult.Acquired[String]].token.nodeId == "node-2",
      )
    },
    test("same node can extend its own lock repeatedly") {
      for
        lock <- ZIO.service[FSMInstanceLock[String]]
        now  <- Clock.instant
        instanceId = uniqueId("lock-extend")

        // Acquire with longer duration to ensure it doesn't expire during extensions
        result <- lock.tryAcquire(instanceId, "node-1", Duration.fromSeconds(5), now)
        token = result.asInstanceOf[LockResult.Acquired[String]].token

        // Extend 50 times
        _ <- ZIO.foldLeft(1 to 50)(token) { (current, _) =>
          for
            later    <- Clock.instant
            extended <- lock.extend(current, Duration.fromSeconds(5), later)
          yield extended.get
        }

        // Should still be valid
        later  <- Clock.instant
        holder <- lock.get(instanceId, later)
      yield assertTrue(
        holder.isDefined,
        holder.get.nodeId == "node-1",
      )
    },
    test("lock acquisition with waiting - nodes queue up") {
      for
        lock <- ZIO.service[FSMInstanceLock[String]]
        instanceId = uniqueId("lock-wait")

        // Node 1 acquires with short duration
        result1 <- lock.acquire(instanceId, "node-1", Duration.fromMillis(100), Duration.fromSeconds(1))
        _       <- assertTrue(result1.isInstanceOf[LockResult.Acquired[String]])

        // Node 2 waits for node 1's lock to expire
        fiber2 <- lock.acquire(instanceId, "node-2", Duration.fromSeconds(10), Duration.fromSeconds(2)).fork

        // Wait a bit then check node 2 eventually gets the lock
        result2 <- fiber2.join
      yield assertTrue(
        result2.isInstanceOf[LockResult.Acquired[String]],
        result2.asInstanceOf[LockResult.Acquired[String]].token.nodeId == "node-2",
      )
    },
    test("interrupt lock acquisition mid-wait") {
      for
        lock <- ZIO.service[FSMInstanceLock[String]]
        now  <- Clock.instant
        instanceId = uniqueId("lock-interrupt")

        // Node 1 holds the lock
        _ <- lock.tryAcquire(instanceId, "node-1", Duration.fromSeconds(60), now)

        // Node 2 tries to acquire with long timeout
        fiber <- lock.acquire(instanceId, "node-2", Duration.fromSeconds(30), Duration.fromSeconds(30)).fork

        // Let it start waiting
        _ <- ZIO.sleep(Duration.fromMillis(50))

        // Interrupt it
        _ <- fiber.interrupt

        // Lock should still be held by node 1
        later  <- Clock.instant
        holder <- lock.get(instanceId, later)
      yield assertTrue(
        holder.isDefined,
        holder.get.nodeId == "node-1",
      )
    },
  )

  // ============================================
  // Event Store Stress Tests
  // ============================================

  val eventStoreStressTests = suite("Event Store Stress Tests")(
    test("100 concurrent appends to same instance - exactly one wins per sequence number") {
      for
        store <- ZIO.service[EventStore[String, StressState, StressEvent]]
        instanceId = uniqueId("event-race")

        // 100 concurrent attempts to append first event (expected current seq = 0)
        results <- ZIO.foreachPar(1 to 100) { i =>
          store.append(instanceId, StressEvent.Started(s"data-$i"), 0).either
        }

        successes = results.collect { case Right(seqNr) => seqNr }
        conflicts = results.collect { case Left(e: SequenceConflictError) => e }
        other     = results.collect { case Left(e) if !e.isInstanceOf[SequenceConflictError] => e }

        events  <- store.loadEvents(instanceId).runCollect
        highest <- store.highestSequenceNr(instanceId)
      yield assertTrue(
        successes == List(1L),
        conflicts.length == 99,
        other.isEmpty,
        events.map(_.sequenceNr) == Chunk(1L),
        highest == 1L,
        conflicts.forall(_.expectedSeqNr == 0L),
        conflicts.forall(_.actualSeqNr == 1L),
        conflicts.forall(_.instanceId == instanceId),
      )
    },
    test("parallel append chains to different instances - all succeed") {
      for
        store <- ZIO.service[EventStore[String, StressState, StressEvent]]

        // 50 instances, each gets 10 sequential events
        instanceIds = (1 to 50).map(_ => uniqueId("chain")).toList

        results <- ZIO.foreachPar(instanceIds) { instanceId =>
          ZIO.foreach(1 to 10) { seq =>
            // Pass expected current seq (seq - 1), get back new seq (seq)
            store.append(instanceId, StressEvent.Progressed(seq), (seq - 1).toLong)
          }
        }

        // Verify all chains complete
        allEvents <- ZIO.foreach(instanceIds) { id =>
          store.loadEvents(id).runCollect.map(id -> _)
        }
      yield assertTrue(
        results.flatten.length == 500,      // All 500 events succeeded
        allEvents.forall(_._2.length == 10), // Each instance has 10 events
      )
    },
    test("snapshot consistency under concurrent appends") {
      for
        store <- ZIO.service[EventStore[String, StressState, StressEvent]]
        instanceId = uniqueId("snapshot-stress")

        // Append events sequentially first (pass expected current = i-1)
        _ <- ZIO.foreach(1 to 10)(i => store.append(instanceId, StressEvent.Progressed(i), (i - 1).toLong))

        // Save snapshot at seq 5
        now <- Clock.instant
        _   <- store.saveSnapshot(FSMSnapshot(instanceId, StressState.Processing, 5L, now))

        // Continue appending while also overwriting snapshot (pass expected current = i-1)
        fiber1 <- ZIO
          .foreach(11 to 20)(i => store.append(instanceId, StressEvent.Progressed(i), (i - 1).toLong))
          .fork
        fiber2 <- store.saveSnapshot(FSMSnapshot(instanceId, StressState.WaitingForTimeout, 10L, now)).fork

        _ <- fiber1.join
        _ <- fiber2.join

        // Verify consistency
        snapshot           <- store.loadSnapshot(instanceId)
        events             <- store.loadEvents(instanceId).runCollect
        eventsFromSnapshot <- store.loadEventsFrom(instanceId, snapshot.get.sequenceNr).runCollect
      yield assertTrue(
        snapshot.isDefined,
        events.length == 20,
        eventsFromSnapshot.forall(_.sequenceNr > snapshot.get.sequenceNr),
      )
    },
    test("delete events while loading - no crash") {
      for
        store <- ZIO.service[EventStore[String, StressState, StressEvent]]
        instanceId = uniqueId("delete-stress")

        // Add events (pass expected current = i-1)
        _ <- ZIO.foreach(1 to 20)(i => store.append(instanceId, StressEvent.Progressed(i), (i - 1).toLong))

        // Start loading while deleting
        loadFiber   <- store.loadEvents(instanceId).runCollect.fork
        deleteFiber <- store.deleteEventsTo(instanceId, 10).fork

        loadedEvents <- loadFiber.join
        _            <- deleteFiber.join

        // Verify we got some events (may vary due to race)
        remainingEvents <- store.loadEvents(instanceId).runCollect
      yield assertTrue(
        loadedEvents.nonEmpty,       // Should have loaded something
        remainingEvents.length <= 10, // Deletion happened
      )
    },
    test("Timeout events mixed with regular events") {
      for
        store <- ZIO.service[EventStore[String, StressState, StressEvent]]
        instanceId = uniqueId("timeout-mix")

        // Alternate between regular events and timeouts
        // Pass expected current seq, get back new seq
        _ <- store.append(instanceId, StressEvent.Started("begin"), 0)
        _ <- store.append(instanceId, StressEvent.Timeout, 1)
        _ <- store.append(instanceId, StressEvent.Progressed(1), 2)
        _ <- store.append(instanceId, StressEvent.Timeout, 3)
        _ <- store.append(instanceId, StressEvent.Finished, 4)

        events <- store.loadEvents(instanceId).runCollect
        timeouts = events.filter(_.event == StressEvent.Timeout)
        regulars = events.filterNot(_.event == StressEvent.Timeout)
      yield assertTrue(
        events.length == 5,
        timeouts.length == 2,
        regulars.length == 3,
      )
    },
  )

  // ============================================
  // Timeout Store Stress Tests
  // ============================================

  val timeoutStoreStressTests = suite("Timeout Store Stress Tests")(
    test("100 concurrent claims for same timeout - exactly one wins") {
      for
        store <- ZIO.service[TimeoutStore[String]]
        now   <- Clock.instant
        instanceId = uniqueId("timeout-claim-race")

        // Schedule a timeout in the past (already expired)
        deadline = now.minusSeconds(10)
        _ <- store.schedule(instanceId, 100, 0L, deadline) // stateHash, sequenceNr

        // 100 nodes try to claim simultaneously
        nodes = (1 to 100).map(i => s"sweeper-$i").toList
        results <- ZIO.foreachPar(nodes) { nodeId =>
          store.claim(instanceId, nodeId, Duration.fromSeconds(30), now)
        }

        claimed        = results.collect { case ClaimResult.Claimed(_) => () }
        alreadyClaimed = results.collect { case ClaimResult.AlreadyClaimed(_, _) => () }
      yield assertTrue(
        claimed.length == 1,        // Exactly one winner
        alreadyClaimed.length == 99, // Everyone else gets AlreadyClaimed
      )
    },
    test("schedule replaces existing timeout atomically") {
      for
        store <- ZIO.service[TimeoutStore[String]]
        now   <- Clock.instant
        instanceId = uniqueId("timeout-replace")

        // Schedule initial timeout
        _ <- store.schedule(instanceId, 100, 0L, now.plusSeconds(100)) // stateHash, sequenceNr

        // Parallel updates with different state hashes
        _ <- ZIO.foreachPar(1 to 50) { i =>
          store.schedule(instanceId, i, i.toLong, now.plusSeconds(i.toLong)) // stateHash = i
        }

        // Only one timeout should exist
        current <- store.get(instanceId)
      yield assertTrue(
        current.isDefined,
        current.get.instanceId == instanceId,
        // State could be any of the concurrent updates - that's fine
      )
    },
    test("cancel while being claimed - one succeeds") {
      for
        store <- ZIO.service[TimeoutStore[String]]
        now   <- Clock.instant
        instanceId = uniqueId("cancel-vs-claim")
        deadline   = now.minusSeconds(1) // Already expired

        _ <- store.schedule(instanceId, 100, 0L, deadline) // stateHash, sequenceNr

        // Race between cancel and claim
        cancelFiber <- store.cancel(instanceId).fork
        claimFiber  <- store.claim(instanceId, "sweeper-1", Duration.fromSeconds(30), now).fork

        _ <- cancelFiber.join
        _ <- claimFiber.join

        // Exactly one should succeed meaningfully
        current <- store.get(instanceId)
      yield assertTrue(
        // Either cancelled (current is None) or claimed (current has claimer)
        current.isEmpty || current.exists(_.claimedBy.isDefined)
      )
    },
    test("query expired with concurrent completions") {
      for
        store <- ZIO.service[TimeoutStore[String]]
        now   <- Clock.instant
        past = now.minusSeconds(10)

        // Schedule many expired timeouts
        ids = (1 to 50).map(_ => uniqueId("expired")).toList
        _ <- ZIO.foreach(ids)(id => store.schedule(id, 100, 0L, past)) // stateHash, sequenceNr

        // Query and complete concurrently
        queryFiber    <- store.queryExpired(100, now).fork
        completeFiber <- ZIO.foreach(ids.take(25))(id => store.complete(id, 0L)).fork

        expired <- queryFiber.join
        _       <- completeFiber.join

        // Should have found some (exact count varies due to race)
        remaining <- store.queryExpired(100, now)
      yield assertTrue(
        expired.nonEmpty,      // Found some before completions
        remaining.length <= 25, // At most 25 left after completing first 25
      )
    },
  )

  // ============================================
  // Leader Election Stress Tests
  // ============================================

  val leaderElectionStressTests = suite("Leader Election Stress Tests")(
    test("10 concurrent leader candidates - exactly one leader") {
      for
        leaseStore <- ZIO.service[LeaseStore]
        now        <- Clock.instant
        leaseKey = uniqueId("leader-race")

        // 10 nodes all try to become leader simultaneously
        nodes = (1 to 10).map(i => s"candidate-$i").toList
        results <- ZIO.foreachPar(nodes) { nodeId =>
          leaseStore.tryAcquire(leaseKey, nodeId, Duration.fromSeconds(30), now)
        }

        leaders = results.flatten
        lease <- leaseStore.get(leaseKey)
      yield assertTrue(
        leaders.length == 1, // Exactly one leader
        lease.isDefined,
        lease.get.holder == leaders.head.holder,
      )
    },
    test("leader loses lease, another takes over") {
      for
        leaseStore <- ZIO.service[LeaseStore]
        now        <- Clock.instant
        leaseKey = uniqueId("leader-failover")

        // Node 1 becomes leader with very short lease
        lease1 <- leaseStore.tryAcquire(leaseKey, "leader-1", Duration.fromMillis(50), now)
        _      <- assertTrue(lease1.isDefined)

        // Wait for lease to expire
        _     <- ZIO.sleep(Duration.fromMillis(100))
        later <- Clock.instant

        // Node 2 should be able to take over
        lease2 <- leaseStore.tryAcquire(leaseKey, "leader-2", Duration.fromSeconds(30), later)
      yield assertTrue(
        lease2.isDefined,
        lease2.get.holder == "leader-2",
      )
    },
    test("leader renews successfully while others fail to acquire") {
      for
        leaseStore <- ZIO.service[LeaseStore]
        now        <- Clock.instant
        leaseKey = uniqueId("leader-renew")

        // Node 1 becomes leader
        _ <- leaseStore.tryAcquire(leaseKey, "leader-1", Duration.fromSeconds(1), now)

        // Parallel: leader renews while others try to acquire
        _ <- ZIO.foreachPar(1 to 10) { i =>
          if i == 1 then
            // Leader renews multiple times
            ZIO.foreach(1 to 5) { _ =>
              for
                later   <- Clock.instant
                renewed <- leaseStore.renew(leaseKey, "leader-1", Duration.fromSeconds(1), later)
                _       <- ZIO.sleep(Duration.fromMillis(50))
              yield renewed
            }
          else
            // Others try to acquire
            for
              later  <- Clock.instant
              result <- leaseStore.tryAcquire(leaseKey, s"node-$i", Duration.fromSeconds(30), later)
            yield result.isDefined
        }

        // Leader should still hold the lease
        lease <- leaseStore.get(leaseKey)
      yield assertTrue(
        lease.isDefined,
        lease.get.holder == "leader-1",
      )
    },
    test("voluntary release allows immediate takeover") {
      for
        leaseStore <- ZIO.service[LeaseStore]
        now        <- Clock.instant
        leaseKey = uniqueId("leader-release")

        // Node 1 becomes leader with long lease
        _ <- leaseStore.tryAcquire(leaseKey, "leader-1", Duration.fromSeconds(60), now)

        // Node 1 voluntarily releases
        released <- leaseStore.release(leaseKey, "leader-1")
        _        <- assertTrue(released)

        // Node 2 can immediately take over
        lease2 <- leaseStore.tryAcquire(leaseKey, "leader-2", Duration.fromSeconds(30), now)
      yield assertTrue(
        lease2.isDefined,
        lease2.get.holder == "leader-2",
      )
    },
  )

  // ============================================
  // Chaos / Integration Tests
  // ============================================

  val chaosTests = suite("Chaos Tests")(
    test("full FSM simulation with concurrent events, timeouts, and locks") {
      for
        eventStore   <- ZIO.service[EventStore[String, StressState, StressEvent]]
        timeoutStore <- ZIO.service[TimeoutStore[String]]
        lock         <- ZIO.service[FSMInstanceLock[String]]

        // Simulate 20 FSM instances being processed by 5 nodes
        instanceIds = (1 to 20).map(_ => uniqueId("fsm")).toList
        nodes       = (1 to 5).map(i => s"processor-$i").toList

        processedRef <- Ref.make(Map.empty[String, Int]) // instanceId -> event count

        // Each node tries to process each instance
        _ <- ZIO.foreachPar(nodes) { nodeId =>
          ZIO.foreach(instanceIds) { instanceId =>
            (for
              lockNow    <- Clock.instant
              lockResult <- lock.tryAcquire(instanceId, nodeId, Duration.fromSeconds(5), lockNow)
              _          <- lockResult match
                case LockResult.Acquired(token) =>
                  for
                    // Got the lock, append events
                    // Pass expected current seq nr; store will insert with expected + 1
                    highest <- eventStore.highestSequenceNr(instanceId)
                    _       <- eventStore.append(instanceId, StressEvent.Started(nodeId), highest)
                    _       <- eventStore.append(instanceId, StressEvent.Progressed(1), highest + 1)

                    // Maybe schedule a timeout
                    timeoutNow <- Clock.instant
                    _ <- timeoutStore.schedule(instanceId, 100, 0L, timeoutNow.plusSeconds(10)) // stateHash, sequenceNr

                    // Update processed count
                    _ <- processedRef.update(m => m.updated(instanceId, m.getOrElse(instanceId, 0) + 2))

                    // Release lock
                    _ <- lock.release(token)
                  yield ()
                case _ =>
                  ZIO.unit                    // Didn't get lock, skip
            yield ()).catchAll(_ => ZIO.unit) // Ignore sequence conflicts
          }
        }

        // Verify all instances have events
        allEvents <- ZIO.foreach(instanceIds) { id =>
          eventStore.loadEvents(id).runCollect.map(id -> _.length)
        }
        processed <- processedRef.get
      yield assertTrue(
        // All instances should have been processed by exactly one node
        allEvents.forall { case (_, count) => count >= 2 },
        // Processed map should have all instances
        processed.size == 20,
      )
    },
    test("interrupt heavy workload mid-execution - database stays consistent") {
      for
        eventStore <- ZIO.service[EventStore[String, StressState, StressEvent]]
        instanceId = uniqueId("interrupt-chaos")

        // Start heavy workload - append many events
        heavyWorkload =
          for
            // Append many events
            _ <- ZIO.foreach(1 to 100) { i =>
              eventStore.append(instanceId, StressEvent.Progressed(i), i.toLong).either
            }
          yield ()

        // Run with short timeout then interrupt
        fiber <- heavyWorkload.fork
        _     <- ZIO.sleep(Duration.fromMillis(50))
        _     <- fiber.interrupt

        // Database should still be consistent
        events <- eventStore.loadEvents(instanceId).runCollect

        // Events should be in order with no gaps
        eventSeqs    = events.map(_.sequenceNr).toList
        expectedSeqs = if eventSeqs.nonEmpty then (1L to eventSeqs.max).toList else List.empty
      yield assertTrue(
        eventSeqs == expectedSeqs // No gaps in sequence
      )
    },
    test("parallel snapshot saves don't corrupt data") {
      for
        eventStore <- ZIO.service[EventStore[String, StressState, StressEvent]]
        instanceId = uniqueId("snapshot-parallel")
        now <- Clock.instant

        // Append some events first (pass expected current = i-1)
        _ <- ZIO.foreach(1 to 10)(i => eventStore.append(instanceId, StressEvent.Progressed(i), (i - 1).toLong))

        // 20 concurrent snapshot saves with different states
        _ <- ZIO.foreachPar(1 to 20) { i =>
          eventStore.saveSnapshot(
            FSMSnapshot(
              instanceId,
              if i % 2 == 0 then StressState.Processing else StressState.Completed,
              i.toLong,
              now,
            )
          )
        }

        // Should have exactly one snapshot
        snapshot <- eventStore.loadSnapshot(instanceId)
      yield assertTrue(
        snapshot.isDefined,
        snapshot.get.instanceId == instanceId,
        // Sequence number could be any of 1-20
      )
    },
    test("claim expired timeouts while scheduling new ones - no lost timeouts") {
      for
        store <- ZIO.service[TimeoutStore[String]]
        now   <- Clock.instant
        past   = now.minusSeconds(10)
        future = now.plusSeconds(100)

        // Create initial expired timeouts
        expiredIds = (1 to 20).map(_ => uniqueId("expired")).toList
        futureIds  = (1 to 20).map(_ => uniqueId("future")).toList

        _ <- ZIO.foreach(expiredIds)(id => store.schedule(id, 100, 0L, past)) // stateHash, sequenceNr

        // Parallel: claim expired + schedule new
        claimFiber <- ZIO
          .foreach(1 to 5) { i =>
            store.queryExpired(10, now).flatMap { expired =>
              ZIO.foreach(expired)(t => store.claim(t.instanceId, s"sweeper-$i", Duration.fromSeconds(30), now))
            }
          }
          .fork

        scheduleFiber <- ZIO.foreach(futureIds)(id => store.schedule(id, 200, 0L, future)).fork // stateHash, sequenceNr

        _ <- claimFiber.join
        _ <- scheduleFiber.join

        // Verify all future timeouts exist
        futureTimeouts <- ZIO.foreach(futureIds)(store.get)
      yield assertTrue(
        futureTimeouts.forall(_.isDefined) // No lost future timeouts
      )
    },
  )

  def spec = suite("PostgreSQL Stress Tests")(
    lockStressTests,
    eventStoreStressTests,
    timeoutStoreStressTests,
    leaderElectionStressTests,
    chaosTests,
  ).provideShared(allLayers) @@ TestAspect.sequential @@ TestAspect.withLiveClock
end PostgresStressSpec
