package mechanoid.persistence.postgres

import zio.*
import zio.test.*
import mechanoid.PostgresTestContainer
import mechanoid.persistence.lock.*

object PostgresInstanceLockSpec extends ZIOSpecDefault:

  val xaLayer   = PostgresTestContainer.DataSourceProvider.transactor
  val lockLayer = xaLayer >>> PostgresInstanceLock.layer

  def spec = suite("PostgresInstanceLock")(
    test("tryAcquire succeeds on unlocked instance") {
      for
        lock   <- ZIO.service[FSMInstanceLock[String]]
        now    <- Clock.instant
        result <- lock.tryAcquire("lock-test-1", "node-1", Duration.fromSeconds(30), now)
      yield result match
        case LockResult.Acquired(token) =>
          assertTrue(
            token.instanceId == "lock-test-1",
            token.nodeId == "node-1",
            token.expiresAt.isAfter(now),
          )
        case _ => assertTrue(false)
    },
    test("tryAcquire returns Busy when locked by another node") {
      for
        lock   <- ZIO.service[FSMInstanceLock[String]]
        now    <- Clock.instant
        _      <- lock.tryAcquire("lock-test-2", "node-1", Duration.fromSeconds(300), now)
        result <- lock.tryAcquire("lock-test-2", "node-2", Duration.fromSeconds(30), now)
      yield result match
        case LockResult.Busy(heldBy, _) => assertTrue(heldBy == "node-1")
        case _                          => assertTrue(false)
    },
    test("tryAcquire succeeds when lock has expired") {
      for
        lock <- ZIO.service[FSMInstanceLock[String]]
        now  <- Clock.instant
        past = now.minusSeconds(60)
        // Acquire with expiry in the past
        _ <- lock.tryAcquire("lock-test-3", "node-1", Duration.fromMillis(1), past)
        // Now try with node-2
        result <- lock.tryAcquire("lock-test-3", "node-2", Duration.fromSeconds(30), now)
      yield result match
        case LockResult.Acquired(token) => assertTrue(token.nodeId == "node-2")
        case _                          => assertTrue(false)
    },
    test("same node can extend its own lock") {
      for
        lock    <- ZIO.service[FSMInstanceLock[String]]
        now     <- Clock.instant
        result1 <- lock.tryAcquire("lock-test-4", "node-1", Duration.fromSeconds(30), now)
        // Same node should be able to re-acquire (reentrant)
        result2 <- lock.tryAcquire("lock-test-4", "node-1", Duration.fromSeconds(60), now)
      yield (result1, result2) match
        case (LockResult.Acquired(_), LockResult.Acquired(token2)) =>
          assertTrue(token2.nodeId == "node-1")
        case _ => assertTrue(false)
    },
    test("release removes the lock") {
      for
        lock   <- ZIO.service[FSMInstanceLock[String]]
        now    <- Clock.instant
        result <- lock.tryAcquire("lock-test-5", "node-1", Duration.fromSeconds(30), now)
        token = result match
          case LockResult.Acquired(t) => t
          case _                      => throw new RuntimeException("Expected lock")
        released  <- lock.release(token)
        retrieved <- lock.get("lock-test-5", now)
      yield assertTrue(
        released,
        retrieved.isEmpty,
      )
    },
    test("release returns false if lock was already released") {
      for
        lock   <- ZIO.service[FSMInstanceLock[String]]
        now    <- Clock.instant
        result <- lock.tryAcquire("lock-test-6", "node-1", Duration.fromSeconds(30), now)
        token = result match
          case LockResult.Acquired(t) => t
          case _                      => throw new RuntimeException("Expected lock")
        _         <- lock.release(token)
        released2 <- lock.release(token)
      yield assertTrue(!released2)
    },
    test("extend prolongs the lock duration") {
      for
        lock   <- ZIO.service[FSMInstanceLock[String]]
        now    <- Clock.instant
        result <- lock.tryAcquire("lock-test-7", "node-1", Duration.fromSeconds(30), now)
        token = result match
          case LockResult.Acquired(t) => t
          case _                      => throw new RuntimeException("Expected lock")
        extended <- lock.extend(token, Duration.fromSeconds(60), now)
      yield extended match
        case Some(newToken) => assertTrue(newToken.expiresAt.isAfter(token.expiresAt))
        case None           => assertTrue(false)
    },
    test("extend returns None if lock was lost") {
      for
        lock   <- ZIO.service[FSMInstanceLock[String]]
        now    <- Clock.instant
        result <- lock.tryAcquire("lock-test-8", "node-1", Duration.fromSeconds(30), now)
        token = result match
          case LockResult.Acquired(t) => t
          case _                      => throw new RuntimeException("Expected lock")
        _        <- lock.release(token)
        extended <- lock.extend(token, Duration.fromSeconds(60), now)
      yield assertTrue(extended.isEmpty)
    },
    test("get returns the current lock holder") {
      for
        lock      <- ZIO.service[FSMInstanceLock[String]]
        now       <- Clock.instant
        _         <- lock.tryAcquire("lock-test-9", "node-1", Duration.fromSeconds(30), now)
        retrieved <- lock.get("lock-test-9", now)
      yield retrieved match
        case Some(token) => assertTrue(token.nodeId == "node-1")
        case None        => assertTrue(false)
    },
    test("get returns None for expired locks") {
      for
        lock <- ZIO.service[FSMInstanceLock[String]]
        now  <- Clock.instant
        past = now.minusSeconds(60)
        // Acquire with expiry in the past
        _         <- lock.tryAcquire("lock-test-10", "node-1", Duration.fromMillis(1), past)
        retrieved <- lock.get("lock-test-10", now)
      yield assertTrue(retrieved.isEmpty)
    },
    test("concurrent acquire - only one wins") {
      for
        lock    <- ZIO.service[FSMInstanceLock[String]]
        now     <- Clock.instant
        results <- ZIO.foreachPar(List("node-a", "node-b", "node-c")) { nodeId =>
          lock.tryAcquire("lock-concurrent", nodeId, Duration.fromSeconds(30), now)
        }
        acquired = results.collect { case LockResult.Acquired(t) => t }
        busy     = results.collect { case b: LockResult.Busy[String] => b }
      yield assertTrue(
        acquired.length == 1,
        busy.length == 2,
      )
    },
  ).provideShared(lockLayer) @@ TestAspect.sequential @@ TestAspect.withLiveClock
end PostgresInstanceLockSpec
