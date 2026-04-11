package mechanoid.stores

import zio.*
import zio.test.*
import mechanoid.persistence.timeout.ClaimResult

object InMemoryTimeoutStoreSpec extends ZIOSpecDefault:

  def spec = suite("InMemoryTimeoutStore")(
    suite("make")(
      test("creates empty store") {
        for
          store <- InMemoryTimeoutStore.make[String]
          size  <- store.size
        yield assertTrue(size == 0)
      }
    ),
    suite("schedule")(
      test("schedules a timeout") {
        for
          store   <- InMemoryTimeoutStore.make[String]
          now     <- Clock.instant
          timeout <- store.schedule("fsm-1", 12345, 1L, now.plusSeconds(60))
        yield assertTrue(
          timeout.instanceId == "fsm-1",
          timeout.stateHash == 12345,
          timeout.sequenceNr == 1L,
        )
      },
      test("overwrites existing timeout for same instance") {
        for
          store   <- InMemoryTimeoutStore.make[String]
          now     <- Clock.instant
          _       <- store.schedule("fsm-1", 111, 1L, now.plusSeconds(60))
          timeout <- store.schedule("fsm-1", 222, 2L, now.plusSeconds(120))
          size    <- store.size
        yield assertTrue(
          size == 1,
          timeout.stateHash == 222,
          timeout.sequenceNr == 2L,
        )
      },
    ),
    suite("cancel")(
      test("removes timeout and returns true") {
        for
          store    <- InMemoryTimeoutStore.make[String]
          now      <- Clock.instant
          _        <- store.schedule("fsm-1", 123, 1L, now.plusSeconds(60))
          canceled <- store.cancel("fsm-1")
          size     <- store.size
        yield assertTrue(canceled, size == 0)
      },
      test("returns false for non-existent instance") {
        for
          store    <- InMemoryTimeoutStore.make[String]
          canceled <- store.cancel("non-existent")
        yield assertTrue(!canceled)
      },
    ),
    suite("queryExpired")(
      test("returns expired unclaimed timeouts") {
        for
          store   <- InMemoryTimeoutStore.make[String]
          now     <- Clock.instant
          _       <- store.schedule("fsm-1", 123, 1L, now.minusSeconds(10))
          _       <- store.schedule("fsm-2", 456, 1L, now.minusSeconds(5))
          expired <- store.queryExpired(10, now)
        yield assertTrue(expired.size == 2)
      },
      test("excludes not-yet-expired timeouts") {
        for
          store   <- InMemoryTimeoutStore.make[String]
          now     <- Clock.instant
          _       <- store.schedule("fsm-1", 123, 1L, now.minusSeconds(10)) // expired
          _       <- store.schedule("fsm-2", 456, 1L, now.plusSeconds(60))  // not expired
          expired <- store.queryExpired(10, now)
        yield assertTrue(
          expired.size == 1,
          expired.head.instanceId == "fsm-1",
        )
      },
      test("excludes claimed timeouts") {
        for
          store   <- InMemoryTimeoutStore.make[String]
          now     <- Clock.instant
          _       <- store.schedule("fsm-1", 123, 1L, now.minusSeconds(10))
          _       <- store.claim("fsm-1", "node-1", 30.seconds, now)
          expired <- store.queryExpired(10, now)
        yield assertTrue(expired.isEmpty)
      },
      test("respects limit") {
        for
          store   <- InMemoryTimeoutStore.make[String]
          now     <- Clock.instant
          _       <- store.schedule("fsm-1", 111, 1L, now.minusSeconds(30))
          _       <- store.schedule("fsm-2", 222, 1L, now.minusSeconds(20))
          _       <- store.schedule("fsm-3", 333, 1L, now.minusSeconds(10))
          expired <- store.queryExpired(2, now)
        yield assertTrue(expired.size == 2)
      },
      test("orders by deadline (oldest first)") {
        for
          store   <- InMemoryTimeoutStore.make[String]
          now     <- Clock.instant
          _       <- store.schedule("fsm-2", 222, 1L, now.minusSeconds(20))
          _       <- store.schedule("fsm-1", 111, 1L, now.minusSeconds(30))
          _       <- store.schedule("fsm-3", 333, 1L, now.minusSeconds(10))
          expired <- store.queryExpired(10, now)
        yield assertTrue(
          expired(0).instanceId == "fsm-1", // oldest
          expired(1).instanceId == "fsm-2",
          expired(2).instanceId == "fsm-3", // newest
        )
      },
    ),
    suite("claim")(
      test("claims unclaimed timeout") {
        for
          store  <- InMemoryTimeoutStore.make[String]
          now    <- Clock.instant
          _      <- store.schedule("fsm-1", 123, 1L, now.minusSeconds(10))
          result <- store.claim("fsm-1", "node-1", 30.seconds, now)
        yield result match
          case ClaimResult.Claimed(timeout) =>
            assertTrue(
              timeout.claimedBy.contains("node-1"),
              timeout.claimedUntil.isDefined,
            )
          case _ => assertTrue(false)
      },
      test("returns NotFound for non-existent timeout") {
        for
          store  <- InMemoryTimeoutStore.make[String]
          now    <- Clock.instant
          result <- store.claim("non-existent", "node-1", 30.seconds, now)
        yield assertTrue(result == ClaimResult.NotFound)
      },
      test("returns AlreadyClaimed for claimed timeout") {
        for
          store  <- InMemoryTimeoutStore.make[String]
          now    <- Clock.instant
          _      <- store.schedule("fsm-1", 123, 1L, now.minusSeconds(10))
          _      <- store.claim("fsm-1", "node-1", 30.seconds, now)
          result <- store.claim("fsm-1", "node-2", 30.seconds, now)
        yield result match
          case ClaimResult.AlreadyClaimed(byNode, _) =>
            assertTrue(byNode == "node-1")
          case _ => assertTrue(false)
      },
      test("allows claiming expired claim") {
        for
          store <- InMemoryTimeoutStore.make[String]
          now   <- Clock.instant
          _     <- store.schedule("fsm-1", 123, 1L, now.minusSeconds(10))
          _     <- store.claim("fsm-1", "node-1", 5.seconds, now.minusSeconds(10))
          // Now the claim is expired, another node can claim
          result <- store.claim("fsm-1", "node-2", 30.seconds, now)
        yield result match
          case ClaimResult.Claimed(timeout) =>
            assertTrue(timeout.claimedBy.contains("node-2"))
          case _ => assertTrue(false)
      },
    ),
    suite("complete")(
      test("removes timeout when sequenceNr matches") {
        for
          store     <- InMemoryTimeoutStore.make[String]
          now       <- Clock.instant
          _         <- store.schedule("fsm-1", 123, 5L, now.plusSeconds(60))
          completed <- store.complete("fsm-1", 5L)
          size      <- store.size
        yield assertTrue(completed, size == 0)
      },
      test("returns false when sequenceNr does not match") {
        for
          store     <- InMemoryTimeoutStore.make[String]
          now       <- Clock.instant
          _         <- store.schedule("fsm-1", 123, 5L, now.plusSeconds(60))
          completed <- store.complete("fsm-1", 999L)
          size      <- store.size
        yield assertTrue(!completed, size == 1)
      },
      test("returns false for non-existent instance") {
        for
          store     <- InMemoryTimeoutStore.make[String]
          completed <- store.complete("non-existent", 1L)
        yield assertTrue(!completed)
      },
    ),
    suite("release")(
      test("clears claim fields") {
        for
          store    <- InMemoryTimeoutStore.make[String]
          now      <- Clock.instant
          _        <- store.schedule("fsm-1", 123, 1L, now.minusSeconds(10))
          _        <- store.claim("fsm-1", "node-1", 30.seconds, now)
          released <- store.release("fsm-1")
          timeout  <- store.get("fsm-1")
        yield assertTrue(
          released,
          timeout.isDefined,
          timeout.get.claimedBy.isEmpty,
          timeout.get.claimedUntil.isEmpty,
        )
      },
      test("returns false for non-existent instance") {
        for
          store    <- InMemoryTimeoutStore.make[String]
          released <- store.release("non-existent")
        yield assertTrue(!released)
      },
    ),
    suite("get")(
      test("returns scheduled timeout") {
        for
          store   <- InMemoryTimeoutStore.make[String]
          now     <- Clock.instant
          _       <- store.schedule("fsm-1", 123, 1L, now.plusSeconds(60))
          timeout <- store.get("fsm-1")
        yield assertTrue(
          timeout.isDefined,
          timeout.get.instanceId == "fsm-1",
        )
      },
      test("returns None for non-existent instance") {
        for
          store   <- InMemoryTimeoutStore.make[String]
          timeout <- store.get("non-existent")
        yield assertTrue(timeout.isEmpty)
      },
    ),
    suite("clear")(
      test("removes all timeouts") {
        for
          store <- InMemoryTimeoutStore.make[String]
          now   <- Clock.instant
          _     <- store.schedule("fsm-1", 111, 1L, now.plusSeconds(60))
          _     <- store.schedule("fsm-2", 222, 1L, now.plusSeconds(60))
          _     <- store.clear
          size  <- store.size
        yield assertTrue(size == 0)
      }
    ),
    suite("getAll")(
      test("returns all timeouts") {
        for
          store <- InMemoryTimeoutStore.make[String]
          now   <- Clock.instant
          _     <- store.schedule("fsm-1", 111, 1L, now.plusSeconds(60))
          _     <- store.schedule("fsm-2", 222, 1L, now.plusSeconds(60))
          all   <- store.getAll
        yield assertTrue(
          all.size == 2,
          all.contains("fsm-1"),
          all.contains("fsm-2"),
        )
      }
    ),
  ) @@ TestAspect.timeout(10.seconds)

end InMemoryTimeoutStoreSpec
