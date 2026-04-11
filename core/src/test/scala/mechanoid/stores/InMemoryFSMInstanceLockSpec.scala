package mechanoid.stores

import zio.*
import zio.test.*
import mechanoid.persistence.lock.{LockResult, LockToken}
import java.time.Instant

object InMemoryFSMInstanceLockSpec extends ZIOSpecDefault:

  def spec = suite("InMemoryFSMInstanceLock")(
    suite("make")(
      test("creates empty lock store") {
        for
          lock  <- InMemoryFSMInstanceLock.make[String]
          count <- lock.activeLockCount
        yield assertTrue(count == 0)
      }
    ),
    suite("tryAcquire")(
      test("acquires lock on available instance") {
        for
          lock   <- InMemoryFSMInstanceLock.make[String]
          now    <- Clock.instant
          result <- lock.tryAcquire("fsm-1", "node-1", 30.seconds, now)
        yield result match
          case LockResult.Acquired(token) =>
            assertTrue(
              token.instanceId == "fsm-1",
              token.nodeId == "node-1",
            )
          case _ => assertTrue(false)
      },
      test("returns Busy when locked by another node") {
        for
          lock   <- InMemoryFSMInstanceLock.make[String]
          now    <- Clock.instant
          _      <- lock.tryAcquire("fsm-1", "node-1", 30.seconds, now)
          result <- lock.tryAcquire("fsm-1", "node-2", 30.seconds, now)
        yield result match
          case LockResult.Busy(heldBy, _) =>
            assertTrue(heldBy == "node-1")
          case _ => assertTrue(false)
      },
      test("allows same node to re-acquire") {
        for
          lock   <- InMemoryFSMInstanceLock.make[String]
          now    <- Clock.instant
          _      <- lock.tryAcquire("fsm-1", "node-1", 30.seconds, now)
          result <- lock.tryAcquire("fsm-1", "node-1", 60.seconds, now)
        yield result match
          case LockResult.Acquired(_) => assertTrue(true)
          case _                      => assertTrue(false)
      },
      test("allows acquiring expired lock") {
        for
          lock   <- InMemoryFSMInstanceLock.make[String]
          past   <- Clock.instant.map(_.minusSeconds(60))
          _      <- lock.tryAcquire("fsm-1", "node-1", 30.seconds, past)
          now    <- Clock.instant
          result <- lock.tryAcquire("fsm-1", "node-2", 30.seconds, now)
        yield result match
          case LockResult.Acquired(token) =>
            assertTrue(token.nodeId == "node-2")
          case _ => assertTrue(false)
      },
    ),
    suite("acquire")(
      test("acquires lock immediately when available") {
        for
          lock   <- InMemoryFSMInstanceLock.make[String]
          result <- lock.acquire("fsm-1", "node-1", 30.seconds, 5.seconds)
        yield result match
          case LockResult.Acquired(_) => assertTrue(true)
          case _                      => assertTrue(false)
      },
      test("returns TimedOut when lock cannot be acquired in time") {
        for
          lock   <- InMemoryFSMInstanceLock.make[String]
          now    <- Clock.instant
          _      <- lock.tryAcquire("fsm-1", "node-1", 60.seconds, now)
          fiber  <- lock.acquire("fsm-1", "node-2", 30.seconds, 100.millis).fork
          _      <- TestClock.adjust(200.millis)
          result <- fiber.join
        yield result match
          case LockResult.TimedOut() => assertTrue(true)
          case _                     => assertTrue(false)
      },
    ),
    suite("release")(
      test("releases lock held by same node") {
        for
          lock   <- InMemoryFSMInstanceLock.make[String]
          now    <- Clock.instant
          result <- lock.tryAcquire("fsm-1", "node-1", 30.seconds, now)
          token = result.asInstanceOf[LockResult.Acquired[String]].token
          released <- lock.release(token)
          count    <- lock.activeLockCount
        yield assertTrue(released, count == 0)
      },
      test("returns false when releasing lock held by different node") {
        for
          lock   <- InMemoryFSMInstanceLock.make[String]
          now    <- Clock.instant
          result <- lock.tryAcquire("fsm-1", "node-1", 30.seconds, now)
          _         = result.asInstanceOf[LockResult.Acquired[String]].token
          fakeToken = LockToken("fsm-1", "node-2", now, now.plusSeconds(30))
          released <- lock.release(fakeToken)
          count    <- lock.activeLockCount
        yield assertTrue(!released, count == 1)
      },
      test("returns false when releasing non-existent lock") {
        for
          lock <- InMemoryFSMInstanceLock.make[String]
          now  <- Clock.instant
          token = LockToken("non-existent", "node-1", now, now.plusSeconds(30))
          released <- lock.release(token)
        yield assertTrue(!released)
      },
    ),
    suite("extend")(
      test("extends lock held by same node") {
        for
          lock   <- InMemoryFSMInstanceLock.make[String]
          now    <- Clock.instant
          result <- lock.tryAcquire("fsm-1", "node-1", 30.seconds, now)
          token = result.asInstanceOf[LockResult.Acquired[String]].token
          newToken <- lock.extend(token, 60.seconds, now)
        yield assertTrue(
          newToken.isDefined,
          newToken.get.expiresAt.isAfter(token.expiresAt),
        )
      },
      test("returns None when lock was lost") {
        for
          lock   <- InMemoryFSMInstanceLock.make[String]
          now    <- Clock.instant
          result <- lock.tryAcquire("fsm-1", "node-1", 30.seconds, now)
          token = result.asInstanceOf[LockResult.Acquired[String]].token
          _        <- lock.release(token)
          newToken <- lock.extend(token, 60.seconds, now)
        yield assertTrue(newToken.isEmpty)
      },
      test("returns None when lock held by different node") {
        for
          lock <- InMemoryFSMInstanceLock.make[String]
          now  <- Clock.instant
          _    <- lock.tryAcquire("fsm-1", "node-1", 30.seconds, now)
          fakeToken = LockToken("fsm-1", "node-2", now, now.plusSeconds(30))
          newToken <- lock.extend(fakeToken, 60.seconds, now)
        yield assertTrue(newToken.isEmpty)
      },
    ),
    suite("get")(
      test("returns active lock") {
        for
          lock   <- InMemoryFSMInstanceLock.make[String]
          now    <- Clock.instant
          _      <- lock.tryAcquire("fsm-1", "node-1", 30.seconds, now)
          result <- lock.get("fsm-1", now)
        yield assertTrue(
          result.isDefined,
          result.get.nodeId == "node-1",
        )
      },
      test("returns None for expired lock") {
        for
          lock   <- InMemoryFSMInstanceLock.make[String]
          past   <- Clock.instant.map(_.minusSeconds(60))
          _      <- lock.tryAcquire("fsm-1", "node-1", 30.seconds, past)
          now    <- Clock.instant
          result <- lock.get("fsm-1", now)
        yield assertTrue(result.isEmpty)
      },
      test("returns None for non-existent lock") {
        for
          lock   <- InMemoryFSMInstanceLock.make[String]
          now    <- Clock.instant
          result <- lock.get("non-existent", now)
        yield assertTrue(result.isEmpty)
      },
    ),
    suite("clear")(
      test("removes all locks") {
        for
          lock  <- InMemoryFSMInstanceLock.make[String]
          now   <- Clock.instant
          _     <- lock.tryAcquire("fsm-1", "node-1", 30.seconds, now)
          _     <- lock.tryAcquire("fsm-2", "node-1", 30.seconds, now)
          _     <- lock.clear
          count <- lock.activeLockCount
        yield assertTrue(count == 0)
      }
    ),
    suite("activeLockCount")(
      test("counts only valid locks") {
        for
          lock  <- InMemoryFSMInstanceLock.make[String]
          past  <- Clock.instant.map(_.minusSeconds(60))
          _     <- lock.tryAcquire("fsm-expired", "node-1", 30.seconds, past)
          now   <- Clock.instant
          _     <- lock.tryAcquire("fsm-valid", "node-1", 30.seconds, now)
          count <- lock.activeLockCount
        yield assertTrue(count == 1)
      }
    ),
  ) @@ TestAspect.timeout(10.seconds)

end InMemoryFSMInstanceLockSpec
