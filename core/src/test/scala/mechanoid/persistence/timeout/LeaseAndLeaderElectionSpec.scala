package mechanoid.persistence.timeout

import zio.*
import zio.test.*
import java.time.Instant

object LeaseAndLeaderElectionSpec extends ZIOSpecDefault:

  def spec = suite("LeaseAndLeaderElectionSpec")(
    suite("Lease")(
      test("isValid returns true before expiration") {
        val now   = Instant.now()
        val lease = Lease("key-1", "node-A", now.plusSeconds(30), now)
        assertTrue(lease.isValid(now))
      },
      test("isValid returns false after expiration") {
        val now   = Instant.now()
        val past  = now.minusSeconds(60)
        val lease = Lease("key-1", "node-A", past.plusSeconds(30), past)
        assertTrue(!lease.isValid(now))
      },
      test("isExpired is opposite of isValid") {
        val now     = Instant.now()
        val valid   = Lease("key-1", "node-A", now.plusSeconds(30), now)
        val expired = Lease("key-1", "node-A", now.minusSeconds(30), now.minusSeconds(60))
        assertTrue(
          !valid.isExpired(now),
          expired.isExpired(now),
          valid.isValid(now) != valid.isExpired(now),
        )
      },
      test("isHeldBy returns true for matching holder") {
        val lease = Lease("key-1", "node-A", Instant.now().plusSeconds(30), Instant.now())
        assertTrue(lease.isHeldBy("node-A"))
      },
      test("isHeldBy returns false for different holder") {
        val lease = Lease("key-1", "node-A", Instant.now().plusSeconds(30), Instant.now())
        assertTrue(!lease.isHeldBy("node-B"))
      },
    ),
    suite("InMemoryLeaseStore")(
      test("tryAcquire succeeds when no lease exists") {
        val store = new InMemoryLeaseStore()
        for
          now    <- Clock.instant
          result <- store.tryAcquire("key-1", "node-A", Duration.fromSeconds(30), now)
        yield assertTrue(
          result.isDefined,
          result.get.holder == "node-A",
          result.get.key == "key-1",
        )
      },
      test("tryAcquire fails when held by another node") {
        val store = new InMemoryLeaseStore()
        for
          now    <- Clock.instant
          _      <- store.tryAcquire("key-1", "node-A", Duration.fromSeconds(30), now)
          result <- store.tryAcquire("key-1", "node-B", Duration.fromSeconds(30), now)
        yield assertTrue(result.isEmpty)
      },
      test("tryAcquire succeeds when re-acquiring by same node") {
        val store = new InMemoryLeaseStore()
        for
          now    <- Clock.instant
          _      <- store.tryAcquire("key-1", "node-A", Duration.fromSeconds(30), now)
          result <- store.tryAcquire("key-1", "node-A", Duration.fromSeconds(60), now)
        yield assertTrue(result.isDefined)
      },
      test("tryAcquire succeeds when lease expired") {
        val store = new InMemoryLeaseStore()
        val past  = Instant.now().minusSeconds(60)
        val now   = Instant.now()
        for
          _      <- store.tryAcquire("key-1", "node-A", Duration.fromSeconds(30), past)
          result <- store.tryAcquire("key-1", "node-B", Duration.fromSeconds(30), now)
        yield assertTrue(
          result.isDefined,
          result.get.holder == "node-B",
        )
      },
      test("renew succeeds when held by same node") {
        val store = new InMemoryLeaseStore()
        for
          now     <- Clock.instant
          _       <- store.tryAcquire("key-1", "node-A", Duration.fromSeconds(30), now)
          renewed <- store.renew("key-1", "node-A", Duration.fromSeconds(60), now)
        yield assertTrue(renewed)
      },
      test("renew fails when not held by this node") {
        val store = new InMemoryLeaseStore()
        for
          now     <- Clock.instant
          _       <- store.tryAcquire("key-1", "node-A", Duration.fromSeconds(30), now)
          renewed <- store.renew("key-1", "node-B", Duration.fromSeconds(60), now)
        yield assertTrue(!renewed)
      },
      test("renew fails when no lease exists") {
        val store = new InMemoryLeaseStore()
        for
          now     <- Clock.instant
          renewed <- store.renew("key-1", "node-A", Duration.fromSeconds(60), now)
        yield assertTrue(!renewed)
      },
      test("release succeeds when held by same node") {
        val store = new InMemoryLeaseStore()
        for
          now      <- Clock.instant
          _        <- store.tryAcquire("key-1", "node-A", Duration.fromSeconds(30), now)
          released <- store.release("key-1", "node-A")
          after    <- store.get("key-1")
        yield assertTrue(released, after.isEmpty)
      },
      test("release fails when not held by this node") {
        val store = new InMemoryLeaseStore()
        for
          now      <- Clock.instant
          _        <- store.tryAcquire("key-1", "node-A", Duration.fromSeconds(30), now)
          released <- store.release("key-1", "node-B")
        yield assertTrue(!released)
      },
      test("release fails when no lease exists") {
        val store = new InMemoryLeaseStore()
        for released <- store.release("key-1", "node-A")
        yield assertTrue(!released)
      },
      test("get returns lease when exists") {
        val store = new InMemoryLeaseStore()
        for
          now   <- Clock.instant
          _     <- store.tryAcquire("key-1", "node-A", Duration.fromSeconds(30), now)
          lease <- store.get("key-1")
        yield assertTrue(
          lease.isDefined,
          lease.get.holder == "node-A",
        )
      },
      test("get returns None when no lease exists") {
        val store = new InMemoryLeaseStore()
        for lease <- store.get("key-1")
        yield assertTrue(lease.isEmpty)
      },
      test("clear removes all leases") {
        val store = new InMemoryLeaseStore()
        for
          now <- Clock.instant
          _   <- store.tryAcquire("key-1", "node-A", Duration.fromSeconds(30), now)
          _   <- store.tryAcquire("key-2", "node-B", Duration.fromSeconds(30), now)
          _ = store.clear()
          l1 <- store.get("key-1")
          l2 <- store.get("key-2")
        yield assertTrue(l1.isEmpty, l2.isEmpty)
      },
      test("expireLease makes lease expired") {
        val store = new InMemoryLeaseStore()
        for
          now <- Clock.instant
          _   <- store.tryAcquire("key-1", "node-A", Duration.fromSeconds(30), now)
          _ = store.expireLease("key-1")
          lease <- store.get("key-1")
        yield assertTrue(
          lease.isDefined,
          lease.get.isExpired(now),
        )
      },
    ),
    suite("LeaderElection")(
      test("alwaysLeader reports true") {
        for
          leader   <- LeaderElection.alwaysLeader
          isLeader <- leader.isLeader
        yield assertTrue(isLeader)
      },
      test("alwaysLeader resign is no-op") {
        for
          leader   <- LeaderElection.alwaysLeader
          _        <- leader.resign
          isLeader <- leader.isLeader
        yield assertTrue(isLeader) // still leader after resign (no-op)
      },
      test("neverLeader reports false") {
        for
          leader   <- LeaderElection.neverLeader
          isLeader <- leader.isLeader
        yield assertTrue(!isLeader)
      },
      test("neverLeader resign is no-op") {
        for
          leader   <- LeaderElection.neverLeader
          _        <- leader.resign
          isLeader <- leader.isLeader
        yield assertTrue(!isLeader)
      },
      test("make acquires leadership when available") {
        val store  = new InMemoryLeaseStore()
        val config = LeaderElectionConfig()
          .withRenewalInterval(Duration.fromSeconds(10))
          .withLeaseDuration(Duration.fromSeconds(30))
          .withLeaseKey("test-leader-1")
        ZIO.scoped {
          for
            leader <- LeaderElection.make(config, "node-1", store)
            // Advance clock and yield to let the forked fiber run
            _ <- TestClock.adjust(Duration.fromMillis(1)) *> ZIO.yieldNow
            // The fiber should have acquired leadership on first iteration
            isLeader <- leader.isLeader
          yield assertTrue(isLeader)
        }
      },
      test("make loses leadership when another node takes lease") {
        val store  = new InMemoryLeaseStore()
        val config = LeaderElectionConfig()
          .withRenewalInterval(Duration.fromSeconds(5))
          .withLeaseDuration(Duration.fromSeconds(20))
          .withLeaseKey("test-leader-2")
        ZIO.scoped {
          for
            leader <- LeaderElection.make(config, "node-1", store)
            // Let fiber acquire leadership
            _         <- TestClock.adjust(Duration.fromMillis(1)) *> ZIO.yieldNow
            isLeader1 <- leader.isLeader
            // Simulate another node forcefully taking the lease
            _ = store.expireLease("test-leader-2")
            now <- Clock.instant
            _   <- store.tryAcquire("test-leader-2", "node-2", Duration.fromSeconds(30), now)
            // Advance past renewal interval to trigger next loop iteration
            _         <- TestClock.adjust(Duration.fromSeconds(6)) *> ZIO.yieldNow
            isLeader2 <- leader.isLeader
          yield assertTrue(isLeader1, !isLeader2)
        }
      },
      test("resign releases leadership") {
        val store  = new InMemoryLeaseStore()
        val config = LeaderElectionConfig()
          .withRenewalInterval(Duration.fromSeconds(10))
          .withLeaseDuration(Duration.fromSeconds(30))
          .withLeaseKey("test-leader-3")
        ZIO.scoped {
          for
            leader     <- LeaderElection.make(config, "node-1", store)
            _          <- TestClock.adjust(Duration.fromMillis(1)) *> ZIO.yieldNow
            wasBefore  <- leader.isLeader
            _          <- leader.resign
            isAfter    <- leader.isLeader
            leaseAfter <- store.get("test-leader-3")
          yield assertTrue(
            wasBefore,
            !isAfter,
            leaseAfter.isEmpty,
          )
        }
      },
      test("leadershipChanges emits events on leadership change") {
        val store  = new InMemoryLeaseStore()
        val config = LeaderElectionConfig()
          .withRenewalInterval(Duration.fromSeconds(5))
          .withLeaseDuration(Duration.fromSeconds(20))
          .withLeaseKey("test-leader-4")
        ZIO.scoped {
          for
            leader <- LeaderElection.make(config, "node-1", store)
            // Let fiber acquire leadership
            _ <- TestClock.adjust(Duration.fromMillis(1)) *> ZIO.yieldNow
            _ <- leader.isLeader.repeatUntil(identity) // Ensure we're leader
            // Subscribe to changes
            fiber <- leader.leadershipChanges.take(1).runCollect.fork
            // Force loss of leadership
            _ = store.expireLease("test-leader-4")
            now <- Clock.instant
            _   <- store.tryAcquire("test-leader-4", "node-2", Duration.fromSeconds(30), now)
            // Advance clock past renewal interval
            _      <- TestClock.adjust(Duration.fromSeconds(6)) *> ZIO.yieldNow
            result <- fiber.join
          yield assertTrue(result.contains(false)) // Should get "false" event for losing leadership
        }
      },
    ),
    suite("LeaderElectionConfig")(
      test("default config has sensible values") {
        val config = LeaderElectionConfig()
        assertTrue(
          config.leaseDuration == Duration.fromSeconds(30),
          config.renewalInterval == Duration.fromSeconds(10),
          config.leaseKey == "mechanoid-timeout-leader",
        )
      },
      test("withLeaseDuration returns modified config") {
        val config = LeaderElectionConfig().withLeaseDuration(Duration.fromSeconds(60))
        assertTrue(config.leaseDuration == Duration.fromSeconds(60))
      },
      test("withRenewalInterval returns modified config") {
        val config = LeaderElectionConfig().withRenewalInterval(Duration.fromSeconds(5))
        assertTrue(config.renewalInterval == Duration.fromSeconds(5))
      },
      test("withLeaseKey returns modified config") {
        val config = LeaderElectionConfig().withLeaseKey("custom-key")
        assertTrue(config.leaseKey == "custom-key")
      },
      test("validation fails when renewalInterval >= leaseDuration") {
        val result =
          try
            // Try to set renewal interval equal to lease duration
            LeaderElectionConfig()
              .withLeaseDuration(Duration.fromSeconds(10))
              .withRenewalInterval(Duration.fromSeconds(10))
            false
          catch case _: IllegalArgumentException => true
        assertTrue(result)
      },
      test("validation fails for non-positive renewalInterval") {
        val result =
          try
            LeaderElectionConfig().withRenewalInterval(Duration.Zero)
            false
          catch case _: IllegalArgumentException => true
        assertTrue(result)
      },
    ),
    suite("TimeoutSweeperConfig builder methods")(
      test("withLeaderElection sets leader election config") {
        val leaderConfig = LeaderElectionConfig()
          .withLeaseKey("test-key")
        val config = TimeoutSweeperConfig()
          .withLeaderElection(leaderConfig)
        assertTrue(
          config.leaderElection.isDefined,
          config.leaderElection.get.leaseKey == "test-key",
        )
      },
      test("withoutLeaderElection removes leader election config") {
        val leaderConfig = LeaderElectionConfig()
        val config       = TimeoutSweeperConfig()
          .withLeaderElection(leaderConfig)
          .withoutLeaderElection
        assertTrue(config.leaderElection.isEmpty)
      },
      test("withBackoffOnEmpty sets backoff duration") {
        val config = TimeoutSweeperConfig()
          .withBackoffOnEmpty(Duration.fromSeconds(10))
        assertTrue(config.backoffOnEmpty.contains(Duration.fromSeconds(10)))
      },
      test("withoutBackoffOnEmpty removes backoff") {
        val config = TimeoutSweeperConfig()
          .withBackoffOnEmpty(Duration.fromSeconds(10))
          .withoutBackoffOnEmpty
        assertTrue(config.backoffOnEmpty.isEmpty)
      },
      test("withSweepInterval sets sweep interval") {
        val config = TimeoutSweeperConfig()
          .withSweepInterval(Duration.fromSeconds(10))
        assertTrue(config.sweepInterval == Duration.fromSeconds(10))
      },
      test("withJitterFactor sets jitter factor") {
        val config = TimeoutSweeperConfig()
          .withJitterFactor(0.5)
        assertTrue(config.jitterFactor == 0.5)
      },
      test("withBatchSize sets batch size") {
        val config = TimeoutSweeperConfig()
          .withBatchSize(50)
        assertTrue(config.batchSize == 50)
      },
      test("withClaimDuration sets claim duration") {
        val config = TimeoutSweeperConfig()
          .withClaimDuration(Duration.fromSeconds(60))
        assertTrue(config.claimDuration == Duration.fromSeconds(60))
      },
      test("withNodeId sets node id") {
        val config = TimeoutSweeperConfig()
          .withNodeId("custom-node-id")
        assertTrue(config.nodeId == "custom-node-id")
      },
    ),
  ) @@ TestAspect.sequential @@ TestAspect.timeout(Duration.fromSeconds(30))
end LeaseAndLeaderElectionSpec
