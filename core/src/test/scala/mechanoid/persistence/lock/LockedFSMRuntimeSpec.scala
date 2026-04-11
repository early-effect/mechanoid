package mechanoid.persistence.lock

import zio.*
import zio.test.*
import mechanoid.core.{ActionFailedError, Finite, LockingError, MechanoidError}
import mechanoid.machine.{Machine, assembly, via}
import mechanoid.runtime.FSMRuntime
import mechanoid.runtime.timeout.TimeoutStrategy
import mechanoid.runtime.locking.LockingStrategy
import mechanoid.stores.{InMemoryEventStore, InMemoryFSMInstanceLock}
import mechanoid.persistence.EventStore

object LockedFSMRuntimeSpec extends ZIOSpecDefault:

  // Simple test state and events
  enum TestState derives Finite:
    case A, B, C, D

  enum TestEvent derives Finite:
    case E1, E2, E3

  import TestState.*
  import TestEvent.*

  // Create a simple machine for testing (no commands needed)
  val testMachine = Machine(
    assembly[TestState, TestEvent](
      A via E1 to B,
      B via E2 to C,
      C via E3 to D,
    )
  )

  // Helper to create a runtime with String ID
  def makeRuntime(id: String): ZIO[Scope, MechanoidError, FSMRuntime[String, TestState, TestEvent]] =
    for
      eventStore <- InMemoryEventStore.makeUnbounded[String, TestState, TestEvent]
      storeLayer = ZLayer.succeed[EventStore[String, TestState, TestEvent]](eventStore)
      runtime <- FSMRuntime(id, testMachine, A).provideSome[Scope](
        storeLayer ++ TimeoutStrategy.fiber[String] ++ LockingStrategy.optimistic[String]
      )
    yield runtime

  def spec = suite("LockedFSMRuntime")(
    suite("send")(
      test("acquires lock and sends event") {
        for
          lock    <- InMemoryFSMInstanceLock.make[String]
          runtime <- makeRuntime("test-send-1")
          lockedRuntime = LockedFSMRuntime.withConfig(runtime, lock, LockConfig.withNodeId("test-node"))
          _     <- lockedRuntime.send(E1)
          state <- lockedRuntime.currentState
        yield assertTrue(
          state == B
        )
      },
      test("releases lock after send completes") {
        for
          lock    <- InMemoryFSMInstanceLock.make[String]
          runtime <- makeRuntime("test-send-2")
          lockedRuntime = LockedFSMRuntime.withConfig(runtime, lock, LockConfig.withNodeId("test-node"))
          _         <- lockedRuntime.send(E1)
          now       <- Clock.instant
          lockAfter <- lock.get("test-send-2", now)
        yield assertTrue(lockAfter.isEmpty)
      },
      test("releases lock on send failure") {
        for
          lock    <- InMemoryFSMInstanceLock.make[String]
          runtime <- makeRuntime("test-send-3")
          lockedRuntime = LockedFSMRuntime.withConfig(runtime, lock, LockConfig.withNodeId("test-node"))
          // Send invalid event (E2 not valid from A)
          result    <- lockedRuntime.send(E2).either
          now       <- Clock.instant
          lockAfter <- lock.get("test-send-3", now)
        yield assertTrue(
          result.isLeft,
          lockAfter.isEmpty,
        )
      },
      test("maps LockError to LockingError") {
        for
          lock <- InMemoryFSMInstanceLock.make[String]
          now  <- Clock.instant
          // Pre-acquire lock with another node
          _       <- lock.tryAcquire("test-send-4", "other-node", Duration.fromSeconds(60), now)
          runtime <- makeRuntime("test-send-4")
          config        = LockConfig.withNodeId("test-node").withAcquireTimeout(Duration.fromMillis(50))
          lockedRuntime = LockedFSMRuntime.withConfig(runtime, lock, config)
          // Fork the send which will timeout
          fiber  <- lockedRuntime.send(E1).either.fork
          _      <- TestClock.adjust(Duration.fromMillis(100))
          result <- fiber.join
        yield result match
          case Left(_: LockingError) => assertTrue(true)
          case _                     => assertTrue(false)
      },
      test("validates lock when validateBeforeOperation is true") {
        for
          lock    <- InMemoryFSMInstanceLock.make[String]
          runtime <- makeRuntime("test-send-5")
          config        = LockConfig.withNodeId("test-node").withValidateBeforeOperation(true)
          lockedRuntime = LockedFSMRuntime.withConfig(runtime, lock, config)
          // This should succeed - lock is acquired and validated
          _     <- lockedRuntime.send(E1)
          state <- lockedRuntime.currentState
        yield assertTrue(state == B)
      },
      test("skips validation when validateBeforeOperation is false") {
        for
          lock    <- InMemoryFSMInstanceLock.make[String]
          runtime <- makeRuntime("test-send-6")
          config        = LockConfig.withNodeId("test-node").withValidateBeforeOperation(false)
          lockedRuntime = LockedFSMRuntime.withConfig(runtime, lock, config)
          _     <- lockedRuntime.send(E1)
          state <- lockedRuntime.currentState
        yield assertTrue(state == B)
      },
    ),
    suite("lock validation error paths")(
      test("validation fails when lock held by another node") {
        // Test LockedFSMRuntime line 75: lock.get returns Some(token) with different nodeId
        // Create a mock lock that returns the other node's token during validation
        val mockLock = new FSMInstanceLock[String]:
          def tryAcquire(instanceId: String, nodeId: String, duration: Duration, now: java.time.Instant) =
            // Return acquired for our node so withLock succeeds initially
            ZIO.succeed(LockResult.Acquired(LockToken(instanceId, nodeId, now, now.plusSeconds(60))))
          def acquire(instanceId: String, nodeId: String, duration: Duration, timeout: Duration) =
            ZIO.succeed(
              LockResult.Acquired(
                LockToken(instanceId, nodeId, java.time.Instant.now(), java.time.Instant.now().plusSeconds(60))
              )
            )
          def release(token: LockToken[String])                                                      = ZIO.succeed(true)
          def extend(token: LockToken[String], additionalDuration: Duration, now: java.time.Instant) =
            ZIO.succeed(Some(token.copy(expiresAt = now.plusSeconds(60))))
          // Return token held by OTHER node during validation
          def get(instanceId: String, now: java.time.Instant) =
            ZIO.succeed(Some(LockToken(instanceId, "other-node", now, now.plusSeconds(60))))

        val config = LockConfig.withNodeId("test-node").withValidateBeforeOperation(true)

        for
          runtime <- makeRuntime("test-validation-1")
          lockedRuntime = LockedFSMRuntime.withConfig(runtime, mockLock, config)
          result <- lockedRuntime.send(E1).either
        yield result match
          case Left(_: LockingError) => assertTrue(true)
          case _                     => assertTrue(false)
      },
      test("validation fails when lock expired/released") {
        // Test LockedFSMRuntime line 80: lock.get returns None
        // Create a mock lock that returns None during validation (lock expired)
        val mockLock = new FSMInstanceLock[String]:
          def tryAcquire(instanceId: String, nodeId: String, duration: Duration, now: java.time.Instant) =
            ZIO.succeed(LockResult.Acquired(LockToken(instanceId, nodeId, now, now.plusSeconds(60))))
          def acquire(instanceId: String, nodeId: String, duration: Duration, timeout: Duration) =
            ZIO.succeed(
              LockResult.Acquired(
                LockToken(instanceId, nodeId, java.time.Instant.now(), java.time.Instant.now().plusSeconds(60))
              )
            )
          def release(token: LockToken[String])                                                      = ZIO.succeed(true)
          def extend(token: LockToken[String], additionalDuration: Duration, now: java.time.Instant) =
            ZIO.succeed(Some(token.copy(expiresAt = now.plusSeconds(60))))
          // Return None - lock expired/released
          def get(instanceId: String, now: java.time.Instant) = ZIO.succeed(None)

        val config = LockConfig.withNodeId("test-node").withValidateBeforeOperation(true)

        for
          runtime <- makeRuntime("test-validation-2")
          lockedRuntime = LockedFSMRuntime.withConfig(runtime, mockLock, config)
          result <- lockedRuntime.send(E1).either
        yield result match
          case Left(_: LockingError) => assertTrue(true)
          case _                     => assertTrue(false)
      },
      test("validation maps MechanoidError to LockAcquisitionFailed") {
        // Test LockedFSMRuntime line 86: lock.get fails with MechanoidError
        // Create a mock lock that fails during get
        val mockLock = new FSMInstanceLock[String]:
          def tryAcquire(instanceId: String, nodeId: String, duration: Duration, now: java.time.Instant) =
            ZIO.succeed(LockResult.Acquired(LockToken(instanceId, nodeId, now, now.plusSeconds(60))))
          def acquire(instanceId: String, nodeId: String, duration: Duration, timeout: Duration) =
            ZIO.succeed(
              LockResult.Acquired(
                LockToken(instanceId, nodeId, java.time.Instant.now(), java.time.Instant.now().plusSeconds(60))
              )
            )
          def release(token: LockToken[String])                                                      = ZIO.succeed(true)
          def extend(token: LockToken[String], additionalDuration: Duration, now: java.time.Instant) =
            ZIO.succeed(Some(token.copy(expiresAt = now.plusSeconds(60))))
          // Fail with a generic error during get
          def get(instanceId: String, now: java.time.Instant) =
            ZIO.fail(mechanoid.core.PersistenceError(new RuntimeException("Database connection lost")))

        val config = LockConfig.withNodeId("test-node").withValidateBeforeOperation(true)

        for
          runtime <- makeRuntime("test-validation-3")
          lockedRuntime = LockedFSMRuntime.withConfig(runtime, mockLock, config)
          result <- lockedRuntime.send(E1).either
        yield result match
          case Left(_: LockingError) => assertTrue(true)
          case _                     => assertTrue(false)
      },
    ),
    suite("delegation")(
      test("instanceId delegates to underlying") {
        for
          lock    <- InMemoryFSMInstanceLock.make[String]
          runtime <- makeRuntime("test-delegate-1")
          lockedRuntime = LockedFSMRuntime.withConfig(runtime, lock, LockConfig.withNodeId("test-node"))
        yield assertTrue(lockedRuntime.instanceId == "test-delegate-1")
      },
      test("currentState delegates to underlying") {
        for
          lock    <- InMemoryFSMInstanceLock.make[String]
          runtime <- makeRuntime("test-delegate-2")
          lockedRuntime = LockedFSMRuntime.withConfig(runtime, lock, LockConfig.withNodeId("test-node"))
          state <- lockedRuntime.currentState
        yield assertTrue(state == A)
      },
      test("state delegates to underlying") {
        for
          lock    <- InMemoryFSMInstanceLock.make[String]
          runtime <- makeRuntime("test-delegate-3")
          lockedRuntime = LockedFSMRuntime.withConfig(runtime, lock, LockConfig.withNodeId("test-node"))
          fsmState <- lockedRuntime.state
        yield assertTrue(fsmState.current == A)
      },
      test("history delegates to underlying") {
        for
          lock    <- InMemoryFSMInstanceLock.make[String]
          runtime <- makeRuntime("test-delegate-4")
          lockedRuntime = LockedFSMRuntime.withConfig(runtime, lock, LockConfig.withNodeId("test-node"))
          history <- lockedRuntime.history
        yield assertTrue(history.isEmpty)
      },
      test("lastSequenceNr delegates to underlying") {
        for
          lock    <- InMemoryFSMInstanceLock.make[String]
          runtime <- makeRuntime("test-delegate-5")
          lockedRuntime = LockedFSMRuntime.withConfig(runtime, lock, LockConfig.withNodeId("test-node"))
          seqNr <- lockedRuntime.lastSequenceNr
        yield assertTrue(seqNr == 0L)
      },
      test("isRunning delegates to underlying") {
        for
          lock    <- InMemoryFSMInstanceLock.make[String]
          runtime <- makeRuntime("test-delegate-6")
          lockedRuntime = LockedFSMRuntime.withConfig(runtime, lock, LockConfig.withNodeId("test-node"))
          running <- lockedRuntime.isRunning
        yield assertTrue(running)
      },
      test("stop delegates to underlying") {
        for
          lock    <- InMemoryFSMInstanceLock.make[String]
          runtime <- makeRuntime("test-delegate-7")
          lockedRuntime = LockedFSMRuntime.withConfig(runtime, lock, LockConfig.withNodeId("test-node"))
          _       <- lockedRuntime.stop
          running <- lockedRuntime.isRunning
        yield assertTrue(!running)
      },
      test("stop with reason delegates to underlying") {
        for
          lock    <- InMemoryFSMInstanceLock.make[String]
          runtime <- makeRuntime("test-delegate-8")
          lockedRuntime = LockedFSMRuntime.withConfig(runtime, lock, LockConfig.withNodeId("test-node"))
          _       <- lockedRuntime.stop("test reason")
          running <- lockedRuntime.isRunning
        yield assertTrue(!running)
      },
      test("machine delegates to underlying") {
        for
          lock    <- InMemoryFSMInstanceLock.make[String]
          runtime <- makeRuntime("test-delegate-9")
          lockedRuntime = LockedFSMRuntime.withConfig(runtime, lock, LockConfig.withNodeId("test-node"))
        yield assertTrue(lockedRuntime.machine == testMachine)
      },
      test("timeoutConfigForState delegates to underlying") {
        for
          lock    <- InMemoryFSMInstanceLock.make[String]
          runtime <- makeRuntime("test-delegate-10")
          lockedRuntime = LockedFSMRuntime.withConfig(runtime, lock, LockConfig.withNodeId("test-node"))
          config        = lockedRuntime.timeoutConfigForState(A)
        yield assertTrue(config.isEmpty) // No timeout configured in testMachine
      },
      test("saveSnapshot delegates to underlying without locking") {
        for
          lock    <- InMemoryFSMInstanceLock.make[String]
          runtime <- makeRuntime("test-delegate-11")
          lockedRuntime = LockedFSMRuntime.withConfig(runtime, lock, LockConfig.withNodeId("test-node"))
          // This should not require a lock
          _ <- lockedRuntime.saveSnapshot
        yield assertTrue(true)
      },
    ),
    suite("withAtomicTransitions")(
      test("holds lock across multiple sends") {
        for
          lock    <- InMemoryFSMInstanceLock.make[String]
          runtime <- makeRuntime("test-1")
          lockedRuntime = LockedFSMRuntime.withConfig(runtime, lock, LockConfig.withNodeId("test-node"))

          // Execute multiple transitions atomically
          result <- lockedRuntime.withAtomicTransitions() { ctx =>
            for
              _      <- ctx.send(E1)
              state1 <- ctx.currentState
              _      <- ctx.send(E2)
              state2 <- ctx.currentState
            yield (state1.current, state2.current)
          }

          finalState <- lockedRuntime.currentState
        yield
          val (s1, s2) = result
          assertTrue(
            s1 == B,
            s2 == C,
            finalState == C,
          )
      },
      test("heartbeat renews lock during transaction") {
        for
          lock       <- InMemoryFSMInstanceLock.make[String]
          renewCount <- Ref.make(0)
          runtime    <- makeRuntime("test-2")

          // Wrap lock to track renewals
          trackingLock = new FSMInstanceLock[String]:
            def tryAcquire(instanceId: String, nodeId: String, duration: Duration, now: java.time.Instant) =
              lock.tryAcquire(instanceId, nodeId, duration, now)
            def acquire(instanceId: String, nodeId: String, duration: Duration, timeout: Duration) =
              lock.acquire(instanceId, nodeId, duration, timeout)
            def release(token: LockToken[String]) = lock.release(token)
            def extend(token: LockToken[String], additionalDuration: Duration, now: java.time.Instant) =
              renewCount.update(_ + 1) *> lock.extend(token, additionalDuration, now)
            def get(instanceId: String, now: java.time.Instant) = lock.get(instanceId, now)

          lockedRuntime = LockedFSMRuntime.withConfig(runtime, trackingLock, LockConfig.withNodeId("test-node"))

          heartbeatConfig = LockHeartbeatConfig(
            renewalInterval = Duration.fromMillis(100),
            renewalDuration = Duration.fromMillis(500),
            jitterFactor = 0.0, // No jitter for deterministic timing
          )

          // Fork the transaction with a sleep that will be controlled by TestClock
          fiber <- lockedRuntime
            .withAtomicTransitions(heartbeatConfig) { _ =>
              ZIO.sleep(Duration.fromMillis(350)) // Will trigger 3 renewals at 100ms intervals
            }
            .fork

          // Advance time to trigger heartbeat renewals deterministically
          // At 100ms: first renewal
          _ <- TestClock.adjust(Duration.fromMillis(100))
          _ <- ZIO.yieldNow // Let heartbeat fiber run
          // At 200ms: second renewal
          _ <- TestClock.adjust(Duration.fromMillis(100))
          _ <- ZIO.yieldNow
          // At 300ms: third renewal
          _ <- TestClock.adjust(Duration.fromMillis(100))
          _ <- ZIO.yieldNow
          // At 350ms: main effect completes
          _ <- TestClock.adjust(Duration.fromMillis(50))
          _ <- fiber.join

          count <- renewCount.get
        yield assertTrue(count >= 2) // Should have renewed at least twice
      },
      // Note: FailFast behavior is tested in FSMInstanceLockSpec.
      // The withAtomicTransitions method uses withLockAndHeartbeat, so the behavior is identical.
      test("releases lock after transaction completes") {
        for
          lock    <- InMemoryFSMInstanceLock.make[String]
          runtime <- makeRuntime("test-5")
          lockedRuntime = LockedFSMRuntime.withConfig(runtime, lock, LockConfig.withNodeId("test-node"))

          _ <- lockedRuntime.withAtomicTransitions() { ctx =>
            ctx.send(E1)
          }

          now       <- Clock.instant
          lockAfter <- lock.get("test-5", now)
        yield assertTrue(lockAfter.isEmpty)
      },
      test("releases lock on transaction failure") {
        for
          lock    <- InMemoryFSMInstanceLock.make[String]
          runtime <- makeRuntime("test-6")
          lockedRuntime = LockedFSMRuntime.withConfig(runtime, lock, LockConfig.withNodeId("test-node"))

          _ <- lockedRuntime
            .withAtomicTransitions() { ctx =>
              ctx.send(E1) *> ZIO.fail(ActionFailedError(new RuntimeException("Test error")))
            }
            .ignore

          now       <- Clock.instant
          lockAfter <- lock.get("test-6", now)
        yield assertTrue(lockAfter.isEmpty)
      },
    ),
    suite("default parameter coverage")(
      test("LockedFSMRuntime.apply uses default config") {
        for
          lock    <- InMemoryFSMInstanceLock.make[String]
          runtime <- makeRuntime("test-default-config")
          // Use apply without config parameter - uses auto-generated nodeId
          lockedRuntime <- LockedFSMRuntime(runtime, lock)
          state         <- lockedRuntime.currentState
        yield assertTrue(state == A)
      }
    ),
  ) @@ TestAspect.sequential @@ TestAspect.timeout(Duration.fromSeconds(30))
end LockedFSMRuntimeSpec
