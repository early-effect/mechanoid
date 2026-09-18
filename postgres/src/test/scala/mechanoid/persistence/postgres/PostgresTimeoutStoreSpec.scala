package mechanoid.persistence.postgres

import zio.*
import zio.test.*
import mechanoid.PostgresTestContainer
import mechanoid.persistence.timeout.*

object PostgresTimeoutStoreSpec extends ZIOSpecDefault:

  val xaLayer    = PostgresTestContainer.DataSourceProvider.transactor
  val storeLayer = xaLayer >>> PostgresTimeoutStore.layer

  // Use constant hashes for testing
  private val StateHash1 = 100
  private val StateHash2 = 200
  private val SeqNr1     = 1L
  private val SeqNr2     = 2L

  def spec = suite("PostgresTimeoutStore")(
    test("schedule creates a new timeout") {
      for
        store <- ZIO.service[TimeoutStore[String]]
        now   <- Clock.instant
        deadline = now.plusSeconds(60)
        timeout <- store.schedule("instance-1", StateHash1, SeqNr1, deadline)
      yield assertTrue(
        timeout.instanceId == "instance-1",
        timeout.stateHash == StateHash1,
        timeout.sequenceNr == SeqNr1,
        timeout.deadline == deadline,
        timeout.claimedBy.isEmpty,
        timeout.claimedUntil.isEmpty,
      )
    },
    test("schedule replaces existing timeout (upsert)") {
      for
        store <- ZIO.service[TimeoutStore[String]]
        now   <- Clock.instant
        deadline1 = now.plusSeconds(60)
        deadline2 = now.plusSeconds(120)
        _         <- store.schedule("instance-2", StateHash1, SeqNr1, deadline1)
        timeout   <- store.schedule("instance-2", StateHash2, SeqNr2, deadline2)
        retrieved <- store.get("instance-2")
      yield assertTrue(
        timeout.stateHash == StateHash2,
        timeout.sequenceNr == SeqNr2,
        timeout.deadline == deadline2,
        retrieved.exists(_.stateHash == StateHash2),
        retrieved.exists(_.sequenceNr == SeqNr2),
      )
    },
    test("cancel removes a timeout") {
      for
        store     <- ZIO.service[TimeoutStore[String]]
        now       <- Clock.instant
        _         <- store.schedule("instance-3", StateHash1, SeqNr1, now.plusSeconds(60))
        cancelled <- store.cancel("instance-3")
        retrieved <- store.get("instance-3")
      yield assertTrue(
        cancelled,
        retrieved.isEmpty,
      )
    },
    test("cancel returns false if no timeout exists") {
      for
        store     <- ZIO.service[TimeoutStore[String]]
        cancelled <- store.cancel("nonexistent-instance")
      yield assertTrue(!cancelled)
    },
    test("queryExpired returns expired unclaimed timeouts") {
      for
        store <- ZIO.service[TimeoutStore[String]]
        now   <- Clock.instant
        past   = now.minusSeconds(10)
        future = now.plusSeconds(60)
        _       <- store.schedule("expired-1", StateHash1, SeqNr1, past)
        _       <- store.schedule("expired-2", StateHash1, SeqNr2, past.minusSeconds(5))
        _       <- store.schedule("not-expired", StateHash1, 3L, future)
        expired <- store.queryExpired(10, now)
      yield assertTrue(
        expired.length == 2,
        expired.map(_.instanceId).toSet == Set("expired-1", "expired-2"),
      )
    },
    test("claim atomically claims a timeout") {
      for
        store <- ZIO.service[TimeoutStore[String]]
        now   <- Clock.instant
        past = now.minusSeconds(10)
        _      <- store.schedule("claim-test-1", StateHash1, SeqNr1, past)
        result <- store.claim("claim-test-1", "node-1", Duration.fromSeconds(30), now)
      yield result match
        case ClaimResult.Claimed(timeout) =>
          assertTrue(
            timeout.instanceId == "claim-test-1",
            timeout.claimedBy.contains("node-1"),
            timeout.claimedUntil.isDefined,
          )
        case _ => assertTrue(false)
    },
    test("claim returns AlreadyClaimed if timeout is claimed by another node") {
      for
        store <- ZIO.service[TimeoutStore[String]]
        now   <- Clock.instant
        past = now.minusSeconds(10)
        _      <- store.schedule("claim-test-2", StateHash1, SeqNr1, past)
        _      <- store.claim("claim-test-2", "node-1", Duration.fromSeconds(300), now)
        result <- store.claim("claim-test-2", "node-2", Duration.fromSeconds(30), now)
      yield result match
        case ClaimResult.AlreadyClaimed(byNode, _) => assertTrue(byNode == "node-1")
        case _                                     => assertTrue(false)
    },
    test("claim succeeds if previous claim has expired") {
      for
        store <- ZIO.service[TimeoutStore[String]]
        now   <- Clock.instant
        past = now.minusSeconds(10)
        _ <- store.schedule("claim-test-3", StateHash1, SeqNr1, past)
        // First claim with very short duration
        _ <- store.claim("claim-test-3", "node-1", Duration.fromMillis(1), now.minusSeconds(5))
        // Sleep past the claim expiry (the claim was in the past, so already expired)
        result <- store.claim("claim-test-3", "node-2", Duration.fromSeconds(30), now)
      yield result match
        case ClaimResult.Claimed(timeout) => assertTrue(timeout.claimedBy.contains("node-2"))
        case _                            => assertTrue(false)
    },
    test("complete removes a timeout") {
      for
        store     <- ZIO.service[TimeoutStore[String]]
        now       <- Clock.instant
        _         <- store.schedule("complete-test", StateHash1, SeqNr1, now.plusSeconds(60))
        completed <- store.complete("complete-test", SeqNr1)
        retrieved <- store.get("complete-test")
      yield assertTrue(
        completed,
        retrieved.isEmpty,
      )
    },
    test("complete does not remove timeout with different sequenceNr") {
      for
        store     <- ZIO.service[TimeoutStore[String]]
        now       <- Clock.instant
        _         <- store.schedule("complete-test-2", StateHash1, SeqNr1, now.plusSeconds(60))
        completed <- store.complete("complete-test-2", SeqNr1 + 1) // Different sequenceNr
        retrieved <- store.get("complete-test-2")
      yield assertTrue(
        !completed,
        retrieved.isDefined, // Should NOT be removed
      )
    },
    test("release clears claim fields") {
      for
        store <- ZIO.service[TimeoutStore[String]]
        now   <- Clock.instant
        past = now.minusSeconds(10)
        _         <- store.schedule("release-test", StateHash1, SeqNr1, past)
        _         <- store.claim("release-test", "node-1", Duration.fromSeconds(30), now)
        released  <- store.release("release-test")
        retrieved <- store.get("release-test")
      yield assertTrue(
        released,
        retrieved.exists(t => t.claimedBy.isEmpty && t.claimedUntil.isEmpty),
      )
    },
    test("get returns None for nonexistent timeout") {
      for
        store  <- ZIO.service[TimeoutStore[String]]
        result <- store.get("does-not-exist")
      yield assertTrue(result.isEmpty)
    },
  ).provideShared(storeLayer) @@ TestAspect.sequential @@ TestAspect.withLiveClock
end PostgresTimeoutStoreSpec
