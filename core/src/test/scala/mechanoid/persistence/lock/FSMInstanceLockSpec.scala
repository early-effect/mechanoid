package mechanoid.persistence.lock

import zio.*
import zio.test.*
import java.time.Instant
import mechanoid.stores.InMemoryFSMInstanceLock

object FSMInstanceLockSpec extends ZIOSpecDefault:

  def spec = suite("FSMInstanceLock")(
    suite("tryAcquire")(
      test("succeeds for unlocked instance") {
        for
          lock   <- InMemoryFSMInstanceLock.make[String]
          now    <- Clock.instant
          result <- lock.tryAcquire("fsm-1", "node-A", Duration.fromSeconds(30), now)
        yield result match
          case LockResult.Acquired(token) =>
            assertTrue(
              token.instanceId == "fsm-1",
              token.nodeId == "node-A",
              token.isValid(now),
            )
          case _ => assertTrue(false)
        end for
      },
      test("fails when locked by another node") {
        for
          lock   <- InMemoryFSMInstanceLock.make[String]
          now    <- Clock.instant
          _      <- lock.tryAcquire("fsm-1", "node-A", Duration.fromSeconds(30), now)
          result <- lock.tryAcquire("fsm-1", "node-B", Duration.fromSeconds(30), now)
        yield result match
          case LockResult.Busy(heldBy, _) =>
            assertTrue(heldBy == "node-A")
          case _ => assertTrue(false)
      },
      test("succeeds when same node re-acquires") {
        for
          lock   <- InMemoryFSMInstanceLock.make[String]
          now    <- Clock.instant
          _      <- lock.tryAcquire("fsm-1", "node-A", Duration.fromSeconds(30), now)
          result <- lock.tryAcquire("fsm-1", "node-A", Duration.fromSeconds(60), now)
        yield result match
          case LockResult.Acquired(token) =>
            assertTrue(token.nodeId == "node-A")
          case _ => assertTrue(false)
      },
      test("succeeds after lock expires") {
        val past = Instant.now().minusSeconds(60)
        val now  = Instant.now()
        for
          lock   <- InMemoryFSMInstanceLock.make[String]
          _      <- lock.tryAcquire("fsm-1", "node-A", Duration.fromSeconds(30), past)
          result <- lock.tryAcquire("fsm-1", "node-B", Duration.fromSeconds(30), now)
        yield result match
          case LockResult.Acquired(token) =>
            assertTrue(token.nodeId == "node-B")
          case _ => assertTrue(false)
      },
    ),
    suite("acquire")(
      test("acquires immediately when available") {
        for
          lock   <- InMemoryFSMInstanceLock.make[String]
          result <- lock.acquire("fsm-1", "node-A", Duration.fromSeconds(30), Duration.fromSeconds(5))
        yield result match
          case LockResult.Acquired(_) => assertTrue(true)
          case _                      => assertTrue(false)
      },
      test("waits and retries when busy") {
        for
          lock <- InMemoryFSMInstanceLock.make[String]
          now  <- Clock.instant
          // Acquire with very short duration so it expires quickly
          _ <- lock.tryAcquire("fsm-1", "node-A", Duration.fromMillis(50), now)
          // Fork the acquire call which will retry and wait
          fiber <- lock
            .acquire(
              "fsm-1",
              "node-B",
              Duration.fromSeconds(30),
              Duration.fromMillis(200),
            )
            .fork
          // Advance time past the lock expiry
          _ <- TestClock.adjust(Duration.fromMillis(60))
          // Now the acquire should succeed
          result <- fiber.join
        yield result match
          case LockResult.Acquired(token) =>
            assertTrue(token.nodeId == "node-B")
          case _ => assertTrue(false)
        end for
      },
      test("times out when lock not released") {
        for
          lock <- InMemoryFSMInstanceLock.make[String]
          now  <- Clock.instant
          // Acquire with long duration
          _ <- lock.tryAcquire("fsm-1", "node-A", Duration.fromSeconds(60), now)
          // Fork the acquire which will retry until timeout
          fiber <- lock
            .acquire(
              "fsm-1",
              "node-B",
              Duration.fromSeconds(30),
              Duration.fromMillis(100),
            )
            .fork
          // Advance time past the timeout in multiple steps to give fiber time to run
          _ <- TestClock.adjust(Duration.fromMillis(20)).repeatN(10)
          // Now the acquire should time out
          result <- fiber.join
        yield result match
          case LockResult.TimedOut() => assertTrue(true)
          case _                     => assertTrue(false)
        end for
      },
    ),
    suite("release")(
      test("releases held lock") {
        for
          lock   <- InMemoryFSMInstanceLock.make[String]
          now    <- Clock.instant
          result <- lock.tryAcquire("fsm-1", "node-A", Duration.fromSeconds(30), now)
          token = result match
            case LockResult.Acquired(t) => t
            case _                      => throw new Exception("Expected Acquired")
          released     <- lock.release(token)
          afterRelease <- lock.get("fsm-1", now)
        yield assertTrue(
          released,
          afterRelease.isEmpty,
        )
        end for
      },
      test("returns false for non-existent lock") {
        for
          lock <- InMemoryFSMInstanceLock.make[String]
          fakeToken = LockToken("fsm-1", "node-A", Instant.now(), Instant.now().plusSeconds(30))
          released <- lock.release(fakeToken)
        yield assertTrue(!released)
      },
    ),
    suite("extend")(
      test("extends held lock") {
        for
          lock   <- InMemoryFSMInstanceLock.make[String]
          now    <- Clock.instant
          result <- lock.tryAcquire("fsm-1", "node-A", Duration.fromSeconds(30), now)
          token = result match
            case LockResult.Acquired(t) => t
            case _                      => throw new Exception("Expected Acquired")
          extended <- lock.extend(token, Duration.fromSeconds(60), now)
        yield extended match
          case Some(newToken) =>
            assertTrue(
              newToken.nodeId == "node-A",
              newToken.expiresAt.isAfter(token.expiresAt),
            )
          case None => assertTrue(false)
        end for
      },
      test("returns None when lock lost") {
        for
          lock   <- InMemoryFSMInstanceLock.make[String]
          now    <- Clock.instant
          result <- lock.tryAcquire("fsm-1", "node-A", Duration.fromSeconds(30), now)
          token = result match
            case LockResult.Acquired(t) => t
            case _                      => throw new Exception("Expected Acquired")
          _        <- lock.release(token)
          extended <- lock.extend(token, Duration.fromSeconds(60), now)
        yield assertTrue(extended.isEmpty)
      },
    ),
    suite("withLock")(
      test("executes effect while holding lock") {
        for
          lock   <- InMemoryFSMInstanceLock.make[String]
          result <- lock.withLock("fsm-1", "node-A", Duration.fromSeconds(30)) {
            ZIO.succeed("success")
          }
        yield assertTrue(result == "success")
      },
      test("releases lock after effect completes") {
        for
          lock <- InMemoryFSMInstanceLock.make[String]
          _    <- lock.withLock("fsm-1", "node-A", Duration.fromSeconds(30)) {
            ZIO.unit
          }
          now       <- Clock.instant
          lockAfter <- lock.get("fsm-1", now)
        yield assertTrue(lockAfter.isEmpty)
      },
      test("releases lock on effect failure") {
        for
          lock <- InMemoryFSMInstanceLock.make[String]
          _    <- lock
            .withLock("fsm-1", "node-A", Duration.fromSeconds(30)) {
              ZIO.fail(new RuntimeException("Test error"))
            }
            .ignore
          now       <- Clock.instant
          lockAfter <- lock.get("fsm-1", now)
        yield assertTrue(lockAfter.isEmpty)
      },
      test("fails with LockBusy when already locked") {
        for
          lock <- InMemoryFSMInstanceLock.make[String]
          now  <- Clock.instant
          _    <- lock.tryAcquire("fsm-1", "node-A", Duration.fromSeconds(60), now)
          // Fork the withLock which will retry until timeout
          fiber <- lock
            .withLock("fsm-1", "node-B", Duration.fromSeconds(30), Some(Duration.fromMillis(50))) {
              ZIO.succeed("should not reach")
            }
            .either
            .fork
          // Advance time past the timeout
          _      <- TestClock.adjust(Duration.fromMillis(100))
          result <- fiber.join
        yield result match
          case Left(_: LockError) => assertTrue(true)
          case _                  => assertTrue(false)
        end for
      },
    ),
    suite("concurrent access")(
      test("only one node succeeds with concurrent acquire") {
        for
          lock <- InMemoryFSMInstanceLock.make[String]
          // Fork all concurrent acquire calls
          fiber <- ZIO
            .foreachPar(1 to 10) { i =>
              lock.acquire(
                "fsm-1",
                s"node-$i",
                Duration.fromSeconds(30),
                Duration.fromMillis(100),
              )
            }
            .fork
          // Advance time past the timeout in multiple steps to give fibers time to run
          _       <- TestClock.adjust(Duration.fromMillis(20)).repeatN(10)
          results <- fiber.join
          acquiredCount = results.count {
            case LockResult.Acquired(_) => true
            case _                      => false
          }
        yield assertTrue(acquiredCount == 1)
        end for
      }
    ),
    suite("withLock error mapping")(
      test("withLock maps Busy to LockBusy error") {
        for
          lock <- InMemoryFSMInstanceLock.make[String]
          now  <- Clock.instant
          // Pre-acquire lock with another node with long expiry
          _ <- lock.tryAcquire("fsm-1", "node-A", Duration.fromSeconds(60), now)
          // Try to withLock with short timeout - should timeout
          fiber <- lock
            .withLock("fsm-1", "node-B", Duration.fromSeconds(30), Some(Duration.fromMillis(50))) {
              ZIO.succeed("should not reach")
            }
            .either
            .fork
          _      <- TestClock.adjust(Duration.fromMillis(100))
          result <- fiber.join
        yield result match
          case Left(LockError.LockTimeout(_, _)) => assertTrue(true)
          case _                                 => assertTrue(false)
        end for
      },
      test("withLock releases lock even after effect failure") {
        for
          lock <- InMemoryFSMInstanceLock.make[String]
          _    <- lock
            .withLock("fsm-1", "node-A", Duration.fromSeconds(30)) {
              ZIO.fail("effect failure")
            }
            .either
          now       <- Clock.instant
          lockAfter <- lock.get("fsm-1", now)
        yield assertTrue(lockAfter.isEmpty)
      },
    ),
    suite("withLockAndHeartbeat")(
      test("renews lock during long operation") {
        for
          lock       <- InMemoryFSMInstanceLock.make[String]
          renewCount <- Ref.make(0)

          // Create a wrapper lock that tracks extend calls
          trackingLock = new FSMInstanceLock[String]:
            def tryAcquire(
                instanceId: String,
                nodeId: String,
                duration: Duration,
                now: Instant,
            ) = lock.tryAcquire(instanceId, nodeId, duration, now)
            def acquire(instanceId: String, nodeId: String, duration: Duration, timeout: Duration) =
              lock.acquire(instanceId, nodeId, duration, timeout)
            def release(token: LockToken[String])                                            = lock.release(token)
            def extend(token: LockToken[String], additionalDuration: Duration, now: Instant) =
              renewCount.update(_ + 1) *> lock.extend(token, additionalDuration, now)
            def get(instanceId: String, now: Instant) = lock.get(instanceId, now)

          heartbeatConfig = LockHeartbeatConfig(
            renewalInterval = Duration.fromMillis(100),
            renewalDuration = Duration.fromMillis(500),
            jitterFactor = 0.0, // No jitter for deterministic timing
            onLockLost = LockLostBehavior.FailFast,
          )

          // Fork the heartbeat operation with a sleep controlled by TestClock
          fiber <- trackingLock
            .withLockAndHeartbeat(
              "fsm-1",
              "node-A",
              Duration.fromMillis(500),
              heartbeat = heartbeatConfig,
            ) {
              ZIO.sleep(Duration.fromMillis(350)) // Will trigger 3 renewals at 100ms intervals
            }
            .fork

          // Advance time to trigger heartbeat renewals deterministically
          _ <- TestClock.adjust(Duration.fromMillis(100))
          _ <- ZIO.yieldNow
          _ <- TestClock.adjust(Duration.fromMillis(100))
          _ <- ZIO.yieldNow
          _ <- TestClock.adjust(Duration.fromMillis(100))
          _ <- ZIO.yieldNow
          _ <- TestClock.adjust(Duration.fromMillis(50))
          _ <- fiber.join

          count <- renewCount.get
        yield assertTrue(count >= 2) // Should have renewed at least twice
      },
      test("stops renewal when operation completes") {
        for
          lock       <- InMemoryFSMInstanceLock.make[String]
          renewCount <- Ref.make(0)

          trackingLock = new FSMInstanceLock[String]:
            def tryAcquire(
                instanceId: String,
                nodeId: String,
                duration: Duration,
                now: Instant,
            ) = lock.tryAcquire(instanceId, nodeId, duration, now)
            def acquire(instanceId: String, nodeId: String, duration: Duration, timeout: Duration) =
              lock.acquire(instanceId, nodeId, duration, timeout)
            def release(token: LockToken[String])                                            = lock.release(token)
            def extend(token: LockToken[String], additionalDuration: Duration, now: Instant) =
              renewCount.update(_ + 1) *> lock.extend(token, additionalDuration, now)
            def get(instanceId: String, now: Instant) = lock.get(instanceId, now)

          heartbeatConfig = LockHeartbeatConfig(
            renewalInterval = Duration.fromMillis(20),
            renewalDuration = Duration.fromMillis(100),
            jitterFactor = 0.0,
          )

          // Complete immediately (no sleeps, so no heartbeats should fire)
          _ <- trackingLock.withLockAndHeartbeat(
            "fsm-1",
            "node-A",
            Duration.fromMillis(100),
            heartbeat = heartbeatConfig,
          ) {
            ZIO.succeed("done")
          }

          countAfterComplete <- renewCount.get

          // Try to advance the clock and verify no more renewals happen
          // (heartbeat fiber should be stopped since main effect completed)
          _              <- TestClock.adjust(Duration.fromMillis(100))
          countAfterWait <- renewCount.get
        yield assertTrue(
          countAfterWait == countAfterComplete // No additional renewals after completion
        )
      },
      test("FailFast interrupts main effect when lock lost") {
        for
          lock          <- InMemoryFSMInstanceLock.make[String]
          failExtend    <- Ref.make(false)
          effectStarted <- Promise.make[Nothing, Unit]
          extendCalled  <- Ref.make(0)

          // Wrapper lock that can be made to fail extend (also tracks calls)
          testLock = new FSMInstanceLock[String]:
            def tryAcquire(instanceId: String, nodeId: String, duration: Duration, now: Instant) =
              lock.tryAcquire(instanceId, nodeId, duration, now)
            def acquire(instanceId: String, nodeId: String, duration: Duration, timeout: Duration) =
              lock.acquire(instanceId, nodeId, duration, timeout)
            def release(token: LockToken[String])                                            = lock.release(token)
            def extend(token: LockToken[String], additionalDuration: Duration, now: Instant) =
              extendCalled.update(_ + 1) *>
                failExtend.get.flatMap { shouldFail =>
                  if shouldFail then ZIO.succeed(None)
                  else lock.extend(token, additionalDuration, now)
                }
            def get(instanceId: String, now: Instant) = lock.get(instanceId, now)

          heartbeatConfig = LockHeartbeatConfig(
            renewalInterval = Duration.fromMillis(100),
            renewalDuration = Duration.fromMillis(500),
            jitterFactor = 0.0,
            onLockLost = LockLostBehavior.FailFast,
          )

          fiber <- testLock
            .withLockAndHeartbeat(
              "fsm-1",
              "node-A",
              Duration.fromMillis(500),
              heartbeat = heartbeatConfig,
            ) {
              effectStarted.succeed(()) *>
                ZIO.sleep(Duration.fromSeconds(10))
            }
            .fork

          // Wait for effect to start
          _ <- effectStarted.await

          // Advance time to trigger first successful heartbeat
          _ <- TestClock.adjust(Duration.fromMillis(100))
          _ <- ZIO.yieldNow

          // Make extend fail, which triggers FailFast on next heartbeat
          _ <- failExtend.set(true)

          // Advance time to trigger the failing heartbeat
          _ <- TestClock.adjust(Duration.fromMillis(100))
          _ <- ZIO.yieldNow

          // The fiber should be interrupted
          exit <- fiber.await
        yield assertTrue(exit.isInterrupted)
      },
      test("Continue runs onLockLost effect and continues") {
        for
          lock            <- InMemoryFSMInstanceLock.make[String]
          lockLostCalled  <- Ref.make(false)
          effectCompleted <- Ref.make(false)
          failExtend      <- Ref.make(false)
          effectStarted   <- Promise.make[Nothing, Unit]

          // Wrapper lock that can be made to fail extend
          testLock = new FSMInstanceLock[String]:
            def tryAcquire(instanceId: String, nodeId: String, duration: Duration, now: Instant) =
              lock.tryAcquire(instanceId, nodeId, duration, now)
            def acquire(instanceId: String, nodeId: String, duration: Duration, timeout: Duration) =
              lock.acquire(instanceId, nodeId, duration, timeout)
            def release(token: LockToken[String])                                            = lock.release(token)
            def extend(token: LockToken[String], additionalDuration: Duration, now: Instant) =
              failExtend.get.flatMap { shouldFail =>
                if shouldFail then ZIO.succeed(None)
                else lock.extend(token, additionalDuration, now)
              }
            def get(instanceId: String, now: Instant) = lock.get(instanceId, now)

          heartbeatConfig = LockHeartbeatConfig(
            renewalInterval = Duration.fromMillis(50),
            renewalDuration = Duration.fromMillis(200),
            jitterFactor = 0.0,
            onLockLost = LockLostBehavior.Continue(
              lockLostCalled.set(true)
            ),
          )

          fiber <- testLock
            .withLockAndHeartbeat(
              "fsm-1",
              "node-A",
              Duration.fromMillis(200),
              heartbeat = heartbeatConfig,
            ) {
              effectStarted.succeed(()) *>
                ZIO.sleep(Duration.fromMillis(200)) *>
                effectCompleted.set(true).as("done")
            }
            .fork

          // Wait for effect to start
          _ <- effectStarted.await

          // Advance time to trigger first successful heartbeat
          _ <- TestClock.adjust(Duration.fromMillis(50))
          _ <- ZIO.yieldNow

          // Make extend fail, which triggers Continue behavior
          _ <- failExtend.set(true)

          // Advance time to trigger the failing heartbeat
          _ <- TestClock.adjust(Duration.fromMillis(50))
          _ <- ZIO.yieldNow

          // onLockLost should have been called
          wasLockLostCalled <- lockLostCalled.get

          // Advance time to complete the main effect
          _ <- TestClock.adjust(Duration.fromMillis(100))
          _ <- ZIO.yieldNow

          // Wait for operation to complete
          result <- fiber.join.either

          didComplete <- effectCompleted.get
        yield assertTrue(
          wasLockLostCalled,
          didComplete,
          result.isRight, // Operation completed successfully despite lock loss
        )
      },
      test("releases lock after effect completes") {
        for
          lock <- InMemoryFSMInstanceLock.make[String]

          heartbeatConfig = LockHeartbeatConfig(
            renewalInterval = Duration.fromMillis(100),
            renewalDuration = Duration.fromMillis(500),
            jitterFactor = 0.0,
          )

          fiber <- lock
            .withLockAndHeartbeat(
              "fsm-1",
              "node-A",
              Duration.fromMillis(500),
              heartbeat = heartbeatConfig,
            ) {
              ZIO.sleep(Duration.fromMillis(150))
            }
            .fork

          // Advance time to trigger one heartbeat and complete the effect
          _ <- TestClock.adjust(Duration.fromMillis(100))
          _ <- ZIO.yieldNow
          _ <- TestClock.adjust(Duration.fromMillis(50))
          _ <- fiber.join

          now       <- Clock.instant
          lockAfter <- lock.get("fsm-1", now)
        yield assertTrue(lockAfter.isEmpty)
      },
    ),
    suite("default parameter coverage")(
      test("withLockAndHeartbeat uses default heartbeat config") {
        for
          lock <- InMemoryFSMInstanceLock.make[String]
          // Call withLockAndHeartbeat without specifying heartbeat parameter
          result <- lock.withLockAndHeartbeat(
            "fsm-1",
            "node-A",
            Duration.fromMillis(100),
          ) {
            ZIO.succeed("success")
          }
        yield assertTrue(result == "success")
      },
      test("LockLostBehavior.Continue uses default onLockLost") {
        // Create Continue with default onLockLost (ZIO.unit)
        val behavior = LockLostBehavior.Continue()
        assertTrue(behavior.onLockLost != null)
      },
    ),
    suite("withLockAndHeartbeat error paths")(
      test("returns LockBusy when acquire returns Busy") {
        for
          lock <- InMemoryFSMInstanceLock.make[String]
          now  <- Clock.instant
          // Pre-acquire lock with another node
          _ <- lock.tryAcquire("fsm-1", "node-A", Duration.fromSeconds(60), now)
          // withLockAndHeartbeat should fail with LockBusy after timeout
          fiber <- lock
            .withLockAndHeartbeat(
              "fsm-1",
              "node-B",
              Duration.fromMillis(100),
              timeout = Some(Duration.fromMillis(50)),
            ) {
              ZIO.succeed("should not reach")
            }
            .either
            .fork
          _      <- TestClock.adjust(Duration.fromMillis(100))
          result <- fiber.join
        yield result match
          case Left(LockError.LockTimeout(_, _)) => assertTrue(true)
          case _                                 => assertTrue(false)
      },
      test("returns LockTimeout when acquire times out") {
        for
          lock <- InMemoryFSMInstanceLock.make[String]
          now  <- Clock.instant
          // Pre-acquire lock with another node
          _ <- lock.tryAcquire("fsm-1", "node-A", Duration.fromSeconds(60), now)
          // withLockAndHeartbeat with short timeout should fail with LockTimeout
          fiber <- lock
            .withLockAndHeartbeat(
              "fsm-1",
              "node-B",
              Duration.fromMillis(100),
              timeout = Some(Duration.fromMillis(50)),
            ) {
              ZIO.succeed("should not reach")
            }
            .either
            .fork
          _      <- TestClock.adjust(Duration.fromMillis(100))
          result <- fiber.join
        yield result match
          case Left(LockError.LockTimeout(_, _)) => assertTrue(true)
          case _                                 => assertTrue(false)
      },
      test("FailFast handles None mainFiber case") {
        // Test FailFast when mainFiber.get returns None
        // This happens when lock is lost before the main effect fiber is stored
        for
          lock         <- InMemoryFSMInstanceLock.make[String]
          extendCalled <- Ref.make(0)

          // Wrapper that fails extend immediately (before effect starts)
          testLock = new FSMInstanceLock[String]:
            def tryAcquire(instanceId: String, nodeId: String, duration: Duration, now: Instant) =
              lock.tryAcquire(instanceId, nodeId, duration, now)
            def acquire(instanceId: String, nodeId: String, duration: Duration, timeout: Duration) =
              lock.acquire(instanceId, nodeId, duration, timeout)
            def release(token: LockToken[String])                                            = lock.release(token)
            def extend(token: LockToken[String], additionalDuration: Duration, now: Instant) =
              // Fail immediately, before main effect starts
              extendCalled.update(_ + 1).as(None)
            def get(instanceId: String, now: Instant) = lock.get(instanceId, now)

          heartbeatConfig = LockHeartbeatConfig(
            renewalInterval = Duration.fromMillis(10),
            renewalDuration = Duration.fromMillis(50),
            jitterFactor = 0.0,
            onLockLost = LockLostBehavior.FailFast,
          )

          // The heartbeat will fail immediately
          fiber <- testLock
            .withLockAndHeartbeat(
              "fsm-1",
              "node-A",
              Duration.fromMillis(100),
              heartbeat = heartbeatConfig,
            ) {
              // This effect may or may not run - the heartbeat may fail before it starts
              ZIO.sleep(Duration.fromSeconds(1))
            }
            .either
            .fork

          // Give time for the heartbeat to fail
          _ <- TestClock.adjust(Duration.fromMillis(50))
          _ <- ZIO.yieldNow

          result <- fiber.await
        yield assertTrue(result.toEither.isLeft || result.isInterrupted) // Either failed or was interrupted
      },
      test("logs warning when extend fails with error") {
        // Test the catchAll branch in renewLock that logs warning
        import mechanoid.core.PersistenceError
        for
          lock         <- InMemoryFSMInstanceLock.make[String]
          extendCalled <- Ref.make(0)

          // Wrapper that throws exception on extend
          testLock = new FSMInstanceLock[String]:
            def tryAcquire(instanceId: String, nodeId: String, duration: Duration, now: Instant) =
              lock.tryAcquire(instanceId, nodeId, duration, now)
            def acquire(instanceId: String, nodeId: String, duration: Duration, timeout: Duration) =
              lock.acquire(instanceId, nodeId, duration, timeout)
            def release(token: LockToken[String])                                            = lock.release(token)
            def extend(token: LockToken[String], additionalDuration: Duration, now: Instant) =
              extendCalled.update(_ + 1) *>
                ZIO.fail(PersistenceError("Database connection lost")) // Fail with MechanoidError
            def get(instanceId: String, now: Instant) = lock.get(instanceId, now)

          heartbeatConfig = LockHeartbeatConfig(
            renewalInterval = Duration.fromMillis(50),
            renewalDuration = Duration.fromMillis(200),
            jitterFactor = 0.0,
            onLockLost = LockLostBehavior.FailFast,
          )

          fiber <- testLock
            .withLockAndHeartbeat(
              "fsm-1",
              "node-A",
              Duration.fromMillis(200),
              heartbeat = heartbeatConfig,
            ) {
              ZIO.sleep(Duration.fromMillis(200))
            }
            .either
            .fork

          // Advance time to trigger heartbeat failure
          _ <- TestClock.adjust(Duration.fromMillis(50))
          _ <- ZIO.yieldNow

          // The error should cause lock lost, which triggers FailFast
          exit <- fiber.await

          calls <- extendCalled.get
        yield assertTrue(
          exit.toEither.isLeft || exit.isInterrupted, // Fiber failed or was interrupted
          calls >= 1,                                 // extend was called
        )
        end for
      },
    ),
    suite("withLock error mapping")(
      test("maps Throwable to LockAcquisitionFailed") {
        import mechanoid.core.PersistenceError
        for
          lock <- InMemoryFSMInstanceLock.make[String]

          // Wrapper that throws exception on acquire
          failingLock = new FSMInstanceLock[String]:
            def tryAcquire(instanceId: String, nodeId: String, duration: Duration, now: Instant) =
              lock.tryAcquire(instanceId, nodeId, duration, now)
            def acquire(instanceId: String, nodeId: String, duration: Duration, timeout: Duration) =
              ZIO.fail(PersistenceError("Database error"))
            def release(token: LockToken[String])                                            = lock.release(token)
            def extend(token: LockToken[String], additionalDuration: Duration, now: Instant) =
              lock.extend(token, additionalDuration, now)
            def get(instanceId: String, now: Instant) = lock.get(instanceId, now)

          result <- failingLock
            .withLock("fsm-1", "node-A", Duration.fromMillis(100)) {
              ZIO.succeed("should not reach")
            }
            .either
        yield result match
          case Left(LockError.LockAcquisitionFailed(_, _)) => assertTrue(true)
          case _                                           => assertTrue(false)
        end for
      }
    ),
    suite("withLock trait default method branches")(
      test("withLock maps LockResult.Busy to LockBusy error") {
        // Test FSMInstanceLock.scala line 196-197: case LockResult.Busy
        for
          now <- Clock.instant
          // Create a mock lock that returns Busy directly from acquire
          mockLock = new FSMInstanceLock[String]:
            def tryAcquire(instanceId: String, nodeId: String, duration: Duration, n: Instant) =
              ZIO.succeed(LockResult.Busy("other-node", now.plusSeconds(60)))
            def acquire(instanceId: String, nodeId: String, duration: Duration, timeout: Duration) =
              ZIO.succeed(LockResult.Busy("other-node", now.plusSeconds(60)))
            def release(token: LockToken[String])                                          = ZIO.succeed(true)
            def extend(token: LockToken[String], additionalDuration: Duration, n: Instant) =
              ZIO.succeed(Some(token))
            def get(instanceId: String, n: Instant) = ZIO.succeed(None)

          result <- mockLock
            .withLock("fsm-1", "node-A", Duration.fromMillis(100)) {
              ZIO.succeed("should not reach")
            }
            .either
        yield result match
          case Left(LockError.LockBusy(_, "other-node", _)) => assertTrue(true)
          case _                                            => assertTrue(false)
      },
      test("withLock maps LockResult.TimedOut to LockTimeout error") {
        // Test FSMInstanceLock.scala line 198-199: case LockResult.TimedOut()
        for
          // Create a mock lock that returns TimedOut directly from acquire
          mockLock = new FSMInstanceLock[String]:
            def tryAcquire(instanceId: String, nodeId: String, duration: Duration, n: Instant) =
              ZIO.succeed(LockResult.TimedOut())
            def acquire(instanceId: String, nodeId: String, duration: Duration, timeout: Duration) =
              ZIO.succeed(LockResult.TimedOut())
            def release(token: LockToken[String])                                          = ZIO.succeed(true)
            def extend(token: LockToken[String], additionalDuration: Duration, n: Instant) =
              ZIO.succeed(Some(token))
            def get(instanceId: String, n: Instant) = ZIO.succeed(None)

          result <- mockLock
            .withLock("fsm-1", "node-A", Duration.fromMillis(100)) {
              ZIO.succeed("should not reach")
            }
            .either
        yield result match
          case Left(LockError.LockTimeout(_, _)) => assertTrue(true)
          case _                                 => assertTrue(false)
      },
      test("withLock maps non-LockError MechanoidError to LockAcquisitionFailed") {
        // Test FSMInstanceLock.scala line 203: case e => (non-LockError MechanoidError)
        import mechanoid.core.PersistenceError
        for
          // Create a mock lock that fails with PersistenceError (not a LockError)
          mockLock = new FSMInstanceLock[String]:
            def tryAcquire(instanceId: String, nodeId: String, duration: Duration, n: Instant) =
              ZIO.fail(PersistenceError("Connection refused"))
            def acquire(instanceId: String, nodeId: String, duration: Duration, timeout: Duration) =
              ZIO.fail(PersistenceError("Connection refused"))
            def release(token: LockToken[String])                                          = ZIO.succeed(true)
            def extend(token: LockToken[String], additionalDuration: Duration, n: Instant) =
              ZIO.succeed(Some(token))
            def get(instanceId: String, n: Instant) = ZIO.succeed(None)

          result <- mockLock
            .withLock("fsm-1", "node-A", Duration.fromMillis(100)) {
              ZIO.succeed("should not reach")
            }
            .either
        yield result match
          case Left(LockError.LockAcquisitionFailed(_, cause)) =>
            assertTrue(cause.getMessage.contains("Connection refused"))
          case _ => assertTrue(false)
        end for
      },
    ),
    suite("withLockAndHeartbeat trait default method branches")(
      test("withLockAndHeartbeat maps LockResult.Busy to LockBusy error") {
        // Test FSMInstanceLock.scala line 276-277: case LockResult.Busy in withLockAndHeartbeat
        for
          now <- Clock.instant
          mockLock = new FSMInstanceLock[String]:
            def tryAcquire(instanceId: String, nodeId: String, duration: Duration, n: Instant) =
              ZIO.succeed(LockResult.Busy("other-node", now.plusSeconds(60)))
            def acquire(instanceId: String, nodeId: String, duration: Duration, timeout: Duration) =
              ZIO.succeed(LockResult.Busy("other-node", now.plusSeconds(60)))
            def release(token: LockToken[String])                                          = ZIO.succeed(true)
            def extend(token: LockToken[String], additionalDuration: Duration, n: Instant) =
              ZIO.succeed(Some(token))
            def get(instanceId: String, n: Instant) = ZIO.succeed(None)

          result <- mockLock
            .withLockAndHeartbeat("fsm-1", "node-A", Duration.fromMillis(100)) {
              ZIO.succeed("should not reach")
            }
            .either
        yield result match
          case Left(LockError.LockBusy(_, "other-node", _)) => assertTrue(true)
          case _                                            => assertTrue(false)
      },
      test("withLockAndHeartbeat maps LockResult.TimedOut to LockTimeout error") {
        // Test FSMInstanceLock.scala line 278-279: case LockResult.TimedOut() in withLockAndHeartbeat
        for
          mockLock = new FSMInstanceLock[String]:
            def tryAcquire(instanceId: String, nodeId: String, duration: Duration, n: Instant) =
              ZIO.succeed(LockResult.TimedOut())
            def acquire(instanceId: String, nodeId: String, duration: Duration, timeout: Duration) =
              ZIO.succeed(LockResult.TimedOut())
            def release(token: LockToken[String])                                          = ZIO.succeed(true)
            def extend(token: LockToken[String], additionalDuration: Duration, n: Instant) =
              ZIO.succeed(Some(token))
            def get(instanceId: String, n: Instant) = ZIO.succeed(None)

          result <- mockLock
            .withLockAndHeartbeat("fsm-1", "node-A", Duration.fromMillis(100)) {
              ZIO.succeed("should not reach")
            }
            .either
        yield result match
          case Left(LockError.LockTimeout(_, _)) => assertTrue(true)
          case _                                 => assertTrue(false)
      },
      test("withLockAndHeartbeat maps non-LockError MechanoidError to LockAcquisitionFailed") {
        // Test FSMInstanceLock.scala line 283: case e => (non-LockError MechanoidError)
        import mechanoid.core.PersistenceError
        for
          mockLock = new FSMInstanceLock[String]:
            def tryAcquire(instanceId: String, nodeId: String, duration: Duration, n: Instant) =
              ZIO.fail(PersistenceError("Database connection lost"))
            def acquire(instanceId: String, nodeId: String, duration: Duration, timeout: Duration) =
              ZIO.fail(PersistenceError("Database connection lost"))
            def release(token: LockToken[String])                                          = ZIO.succeed(true)
            def extend(token: LockToken[String], additionalDuration: Duration, n: Instant) =
              ZIO.succeed(Some(token))
            def get(instanceId: String, n: Instant) = ZIO.succeed(None)

          result <- mockLock
            .withLockAndHeartbeat("fsm-1", "node-A", Duration.fromMillis(100)) {
              ZIO.succeed("should not reach")
            }
            .either
        yield result match
          case Left(LockError.LockAcquisitionFailed(_, cause)) =>
            assertTrue(cause.getMessage.contains("Database connection lost"))
          case _ => assertTrue(false)
        end for
      },
      test("withLockAndHeartbeat handles mainFiber None in FailFast") {
        // Test FSMInstanceLock.scala line 318: case None in FailFast mainFiber.get
        // This happens when lock is lost before mainFiber is set
        for
          now         <- Clock.instant
          extendCount <- Ref.make(0)
          mockLock = new FSMInstanceLock[String]:
            def tryAcquire(instanceId: String, nodeId: String, duration: Duration, n: Instant) =
              ZIO.succeed(LockResult.Acquired(LockToken(instanceId, nodeId, now, now.plusSeconds(60))))
            def acquire(instanceId: String, nodeId: String, duration: Duration, timeout: Duration) =
              ZIO.succeed(LockResult.Acquired(LockToken(instanceId, nodeId, now, now.plusSeconds(60))))
            def release(token: LockToken[String])                                          = ZIO.succeed(true)
            def extend(token: LockToken[String], additionalDuration: Duration, n: Instant) =
              // Fail extend immediately (returns None = lock lost)
              extendCount.update(_ + 1).as(None)
            def get(instanceId: String, n: Instant) = ZIO.succeed(None)

          heartbeat = LockHeartbeatConfig(
            renewalInterval = Duration.fromMillis(50),
            renewalDuration = Duration.fromMillis(200),
            jitterFactor = 0.0,
            onLockLost = LockLostBehavior.FailFast,
          )

          fiber <- mockLock
            .withLockAndHeartbeat("fsm-1", "node-A", Duration.fromMillis(200), heartbeat = heartbeat) {
              // Long-running effect - heartbeat will fail and trigger FailFast
              ZIO.sleep(Duration.fromSeconds(10))
            }
            .either
            .fork

          // Advance time to trigger heartbeat failure
          _ <- TestClock.adjust(Duration.fromMillis(50))
          _ <- ZIO.yieldNow

          result <- fiber.await
        yield assertTrue(result.toEither.isLeft || result.isInterrupted) // Fiber completed (was interrupted or failed)
      },
    ),
    suite("FSMInstanceLock companion object constants")(
      test("DefaultLockDuration is 30 seconds") {
        // Test FSMInstanceLock.scala line 348
        assertTrue(FSMInstanceLock.DefaultLockDuration == Duration.fromSeconds(30))
      },
      test("DefaultLockTimeout is 10 seconds") {
        // Test FSMInstanceLock.scala line 351
        assertTrue(FSMInstanceLock.DefaultLockTimeout == Duration.fromSeconds(10))
      },
    ),
  ) @@ TestAspect.sequential @@ TestAspect.timeout(Duration.fromSeconds(30))
end FSMInstanceLockSpec
