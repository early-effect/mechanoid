package mechanoid.persistence.timeout

import zio.*
import zio.test.*
import java.time.Instant

object TimeoutStoreSpec extends ZIOSpecDefault:

  // Use constant hashes for testing - actual values don't matter for store tests
  private val StateHash1 = 100
  private val StateHash2 = 200
  private val SeqNr1     = 1L
  private val SeqNr2     = 2L

  def spec = suite("TimeoutStore")(
    suite("schedule")(
      test("creates a new timeout") {
        val store    = new InMemoryTimeoutStore[String]()
        val deadline = Instant.now().plusSeconds(60)
        for timeout <- store.schedule("fsm-1", StateHash1, SeqNr1, deadline)
        yield assertTrue(
          timeout.instanceId == "fsm-1",
          timeout.stateHash == StateHash1,
          timeout.sequenceNr == SeqNr1,
          timeout.deadline == deadline,
          timeout.claimedBy.isEmpty,
          timeout.claimedUntil.isEmpty,
        )
      },
      test("replaces existing timeout (upsert semantics)") {
        val store     = new InMemoryTimeoutStore[String]()
        val deadline1 = Instant.now().plusSeconds(60)
        val deadline2 = Instant.now().plusSeconds(120)
        for
          _      <- store.schedule("fsm-1", StateHash1, SeqNr1, deadline1)
          _      <- store.schedule("fsm-1", StateHash2, SeqNr2, deadline2)
          stored <- store.get("fsm-1")
        yield assertTrue(
          stored.isDefined,
          stored.get.stateHash == StateHash2,
          stored.get.sequenceNr == SeqNr2,
          stored.get.deadline == deadline2,
        )
        end for
      },
      test("handles multiple instances independently") {
        val store = new InMemoryTimeoutStore[String]()
        val now   = Instant.now()
        for
          _  <- store.schedule("fsm-1", StateHash1, SeqNr1, now.plusSeconds(60))
          _  <- store.schedule("fsm-2", StateHash2, SeqNr2, now.plusSeconds(120))
          t1 <- store.get("fsm-1")
          t2 <- store.get("fsm-2")
        yield assertTrue(
          t1.get.stateHash == StateHash1,
          t2.get.stateHash == StateHash2,
          store.size == 2,
        )
        end for
      },
    ),
    suite("cancel")(
      test("removes existing timeout") {
        val store = new InMemoryTimeoutStore[String]()
        for
          _         <- store.schedule("fsm-1", StateHash1, SeqNr1, Instant.now().plusSeconds(60))
          cancelled <- store.cancel("fsm-1")
          stored    <- store.get("fsm-1")
        yield assertTrue(cancelled, stored.isEmpty)
      },
      test("returns false for non-existent timeout") {
        val store = new InMemoryTimeoutStore[String]()
        for cancelled <- store.cancel("non-existent")
        yield assertTrue(!cancelled)
      },
    ),
    suite("queryExpired")(
      test("returns only expired unclaimed timeouts") {
        val store = new InMemoryTimeoutStore[String]()
        val now   = Instant.now()
        for
          _       <- store.schedule("expired-1", StateHash1, SeqNr1, now.minusSeconds(10))
          _       <- store.schedule("expired-2", StateHash1, SeqNr1, now.minusSeconds(5))
          _       <- store.schedule("future", StateHash1, SeqNr1, now.plusSeconds(60))
          expired <- store.queryExpired(10, now)
        yield assertTrue(
          expired.length == 2,
          expired.map(_.instanceId).toSet == Set("expired-1", "expired-2"),
        )
      },
      test("orders by deadline (oldest first)") {
        val store = new InMemoryTimeoutStore[String]()
        val now   = Instant.now()
        for
          _       <- store.schedule("newer", StateHash1, SeqNr1, now.minusSeconds(5))
          _       <- store.schedule("older", StateHash1, SeqNr1, now.minusSeconds(10))
          _       <- store.schedule("newest", StateHash1, SeqNr1, now.minusSeconds(1))
          expired <- store.queryExpired(10, now)
        yield assertTrue(
          expired.map(_.instanceId) == List("older", "newer", "newest")
        )
      },
      test("respects limit parameter") {
        val store = new InMemoryTimeoutStore[String]()
        val now   = Instant.now()
        for
          _ <- ZIO.foreach(1 to 10)(i => store.schedule(s"fsm-$i", StateHash1, i.toLong, now.minusSeconds(i.toLong)))
          expired <- store.queryExpired(3, now)
        yield assertTrue(expired.length == 3)
      },
      test("excludes claimed timeouts with valid claims") {
        val store = new InMemoryTimeoutStore[String]()
        val now   = Instant.now()
        for
          _       <- store.schedule("unclaimed", StateHash1, SeqNr1, now.minusSeconds(10))
          _       <- store.schedule("claimed", StateHash1, SeqNr2, now.minusSeconds(10))
          _       <- store.claim("claimed", "node-A", Duration.fromSeconds(30), now)
          expired <- store.queryExpired(10, now)
        yield assertTrue(
          expired.length == 1,
          expired.head.instanceId == "unclaimed",
        )
      },
      test("includes timeouts with expired claims") {
        val store = new InMemoryTimeoutStore[String]()
        val past  = Instant.now().minusSeconds(60)
        val now   = Instant.now()
        for
          _ <- store.schedule("fsm-1", StateHash1, SeqNr1, past)
          // Claim in the past (claim has expired)
          _       <- store.claim("fsm-1", "node-A", Duration.fromSeconds(30), past)
          expired <- store.queryExpired(10, now)
        yield assertTrue(expired.length == 1)
      },
    ),
    suite("claim")(
      test("succeeds for unclaimed timeout") {
        val store = new InMemoryTimeoutStore[String]()
        val now   = Instant.now()
        for
          _      <- store.schedule("fsm-1", StateHash1, SeqNr1, now.minusSeconds(10))
          result <- store.claim("fsm-1", "node-A", Duration.fromSeconds(30), now)
        yield result match
          case ClaimResult.Claimed(t) =>
            assertTrue(
              t.claimedBy.contains("node-A"),
              t.claimedUntil.exists(_.isAfter(now)),
            )
          case _ => assertTrue(false)
        end for
      },
      test("fails for already claimed timeout") {
        val store = new InMemoryTimeoutStore[String]()
        val now   = Instant.now()
        for
          _      <- store.schedule("fsm-1", StateHash1, SeqNr1, now.minusSeconds(10))
          _      <- store.claim("fsm-1", "node-A", Duration.fromSeconds(30), now)
          result <- store.claim("fsm-1", "node-B", Duration.fromSeconds(30), now)
        yield result match
          case ClaimResult.AlreadyClaimed(byNode, _) =>
            assertTrue(byNode == "node-A")
          case _ => assertTrue(false)
      },
      test("succeeds after claim expires") {
        val store = new InMemoryTimeoutStore[String]()
        val past  = Instant.now().minusSeconds(60)
        val now   = Instant.now()
        for
          _ <- store.schedule("fsm-1", StateHash1, SeqNr1, past.minusSeconds(10))
          // Claim in the past (will be expired by now)
          _ <- store.claim("fsm-1", "node-A", Duration.fromSeconds(30), past)
          // Now try to claim - should succeed since previous claim expired
          result <- store.claim("fsm-1", "node-B", Duration.fromSeconds(30), now)
        yield result match
          case ClaimResult.Claimed(t) =>
            assertTrue(t.claimedBy.contains("node-B"))
          case _ => assertTrue(false)
        end for
      },
      test("returns NotFound for non-existent timeout") {
        val store = new InMemoryTimeoutStore[String]()
        for result <- store.claim(
            "non-existent",
            "node-A",
            Duration.fromSeconds(30),
            Instant.now(),
          )
        yield assertTrue(result == ClaimResult.NotFound)
      },
    ),
    suite("complete")(
      test("removes timeout after successful processing") {
        val store = new InMemoryTimeoutStore[String]()
        for
          _         <- store.schedule("fsm-1", StateHash1, SeqNr1, Instant.now().minusSeconds(10))
          completed <- store.complete("fsm-1", SeqNr1)
          stored    <- store.get("fsm-1")
        yield assertTrue(completed, stored.isEmpty)
      },
      test("returns false for non-existent timeout") {
        val store = new InMemoryTimeoutStore[String]()
        for completed <- store.complete("non-existent", SeqNr1)
        yield assertTrue(!completed)
      },
      test("returns false when sequenceNr does not match") {
        val store = new InMemoryTimeoutStore[String]()
        for
          _         <- store.schedule("fsm-1", StateHash1, SeqNr1, Instant.now().minusSeconds(10))
          completed <- store.complete("fsm-1", SeqNr1 + 1) // Different sequenceNr
          stored    <- store.get("fsm-1")
        yield assertTrue(!completed, stored.isDefined) // Should NOT be removed
      },
    ),
    suite("release")(
      test("clears claim to allow retry") {
        val store = new InMemoryTimeoutStore[String]()
        val now   = Instant.now()
        for
          _        <- store.schedule("fsm-1", StateHash1, SeqNr1, now.minusSeconds(10))
          _        <- store.claim("fsm-1", "node-A", Duration.fromSeconds(30), now)
          released <- store.release("fsm-1")
          stored   <- store.get("fsm-1")
        yield assertTrue(
          released,
          stored.isDefined,
          stored.get.claimedBy.isEmpty,
          stored.get.claimedUntil.isEmpty,
        )
        end for
      },
      test("allows re-claim after release") {
        val store = new InMemoryTimeoutStore[String]()
        val now   = Instant.now()
        for
          _      <- store.schedule("fsm-1", StateHash1, SeqNr1, now.minusSeconds(10))
          _      <- store.claim("fsm-1", "node-A", Duration.fromSeconds(30), now)
          _      <- store.release("fsm-1")
          result <- store.claim("fsm-1", "node-B", Duration.fromSeconds(30), now)
        yield result match
          case ClaimResult.Claimed(t) =>
            assertTrue(t.claimedBy.contains("node-B"))
          case _ => assertTrue(false)
      },
    ),
    suite("ScheduledTimeout helpers")(
      test("isClaimed returns true for valid claim") {
        val now     = Instant.now()
        val timeout = ScheduledTimeout(
          instanceId = "fsm-1",
          stateHash = StateHash1,
          sequenceNr = SeqNr1,
          deadline = now.minusSeconds(10),
          createdAt = now.minusSeconds(20),
          claimedBy = Some("node-A"),
          claimedUntil = Some(now.plusSeconds(30)),
        )
        assertTrue(timeout.isClaimed(now))
      },
      test("isClaimed returns false for expired claim") {
        val now     = Instant.now()
        val timeout = ScheduledTimeout(
          instanceId = "fsm-1",
          stateHash = StateHash1,
          sequenceNr = SeqNr1,
          deadline = now.minusSeconds(10),
          createdAt = now.minusSeconds(20),
          claimedBy = Some("node-A"),
          claimedUntil = Some(now.minusSeconds(1)),
        )
        assertTrue(!timeout.isClaimed(now))
      },
      test("canBeClaimed returns true for expired unclaimed timeout") {
        val now     = Instant.now()
        val timeout = ScheduledTimeout(
          instanceId = "fsm-1",
          stateHash = StateHash1,
          sequenceNr = SeqNr1,
          deadline = now.minusSeconds(10),
          createdAt = now.minusSeconds(20),
        )
        assertTrue(timeout.canBeClaimed(now))
      },
    ),
  ) @@ TestAspect.sequential @@ TestAspect.timeout(Duration.fromSeconds(30))
end TimeoutStoreSpec
