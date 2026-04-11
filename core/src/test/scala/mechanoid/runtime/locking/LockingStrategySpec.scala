package mechanoid.runtime.locking

import zio.*
import zio.test.*
import mechanoid.core.{LockingError, MechanoidError, FSMStoppedError}
import mechanoid.persistence.lock.*
import mechanoid.stores.InMemoryFSMInstanceLock

object LockingStrategySpec extends ZIOSpecDefault:

  def spec = suite("LockingStrategySpec")(
    suite("OptimisticLockingStrategy")(
      test("withLock executes effect directly without locking") {
        val strategy = OptimisticLockingStrategy.make[String]
        for result <- strategy.withLock("instance-1", ZIO.succeed(42))
        yield assertTrue(result == 42)
      },
      test("withLock propagates effect success") {
        val strategy = OptimisticLockingStrategy.make[String]
        for result <- strategy.withLock("instance-1", ZIO.succeed("success"))
        yield assertTrue(result == "success")
      },
      test("withLock propagates effect failure") {
        val strategy = OptimisticLockingStrategy.make[String]
        val error    = new RuntimeException("test error")
        for result <- strategy.withLock("instance-1", ZIO.fail(error)).either
        yield result match
          case Left(e)  => assertTrue(e == error)
          case Right(_) => assertTrue(false)
      },
      test("make creates new instance") {
        val s1 = OptimisticLockingStrategy.make[String]
        val s2 = OptimisticLockingStrategy.make[Int]
        assertTrue(s1 != null, s2 != null)
      },
      test("layer provides strategy service") {
        for result <- ZIO
            .serviceWithZIO[LockingStrategy[String]](_.withLock("test", ZIO.succeed(123)))
            .provideLayer(OptimisticLockingStrategy.layer[String])
        yield assertTrue(result == 123)
      },
    ),
    suite("DistributedLockingStrategy")(
      test("withLock acquires lock before executing effect") {
        for
          lock     <- InMemoryFSMInstanceLock.make[String]
          strategy <- DistributedLockingStrategy.make(lock)
          lockHeld <- Ref.make(false)
          _        <- strategy.withLock(
            "instance-1",
            // Check if lock is held during effect execution
            Clock.instant.flatMap(now => lock.get("instance-1", now)).flatMap {
              case Some(_) => lockHeld.set(true)
              case None    => ZIO.unit
            },
          )
          wasHeld <- lockHeld.get
        yield assertTrue(wasHeld)
      },
      test("withLock releases lock after effect completes") {
        for
          lock     <- InMemoryFSMInstanceLock.make[String]
          strategy <- DistributedLockingStrategy.make(lock)
          _        <- strategy.withLock("instance-1", ZIO.succeed("done"))
          now      <- Clock.instant
          lockHeld <- lock.get("instance-1", now)
        yield assertTrue(lockHeld.isEmpty)
      },
      test("withLock releases lock on effect failure") {
        for
          lock     <- InMemoryFSMInstanceLock.make[String]
          strategy <- DistributedLockingStrategy.make(lock)
          _        <- strategy.withLock("instance-1", ZIO.fail(new RuntimeException("error"))).ignore
          now      <- Clock.instant
          lockHeld <- lock.get("instance-1", now)
        yield assertTrue(lockHeld.isEmpty)
      },
      test("withLock returns effect result on success") {
        for
          lock     <- InMemoryFSMInstanceLock.make[String]
          strategy <- DistributedLockingStrategy.make(lock)
          result   <- strategy.withLock("instance-1", ZIO.succeed(42))
        yield assertTrue(result == 42)
      },
      test("withLock returns LockingError when lock busy and times out") {
        for
          lock <- InMemoryFSMInstanceLock.make[String]
          now  <- Clock.instant
          // Pre-acquire lock by another node
          _ <- lock.tryAcquire("instance-1", "other-node", Duration.fromSeconds(60), now)
          // Use short timeout config
          config   = LockConfig.withNodeId("test-node").withAcquireTimeout(Duration.fromMillis(50))
          strategy = DistributedLockingStrategy.make(lock, config)
          // Fork the attempt which will timeout
          fiber <- strategy.withLock("instance-1", ZIO.succeed("should not reach")).either.fork
          // Advance time past the timeout
          _      <- TestClock.adjust(Duration.fromMillis(100))
          result <- fiber.join
        yield result match
          case Left(_: LockingError) => assertTrue(true)
          case _                     => assertTrue(false)
      },
      test("withLock with validateBeforeOperation=true validates lock") {
        for
          lock <- InMemoryFSMInstanceLock.make[String]
          config   = LockConfig.withNodeId("test-node").withValidateBeforeOperation(true)
          strategy = DistributedLockingStrategy.make(lock, config)
          // This should succeed - lock is held by us
          result <- strategy.withLock("instance-1", ZIO.succeed("success"))
        yield assertTrue(result == "success")
      },
      test("withLock with validateBeforeOperation=false skips validation") {
        for
          lock <- InMemoryFSMInstanceLock.make[String]
          config   = LockConfig.withNodeId("test-node").withValidateBeforeOperation(false)
          strategy = DistributedLockingStrategy.make(lock, config)
          result <- strategy.withLock("instance-1", ZIO.succeed("success"))
        yield assertTrue(result == "success")
      },
      test("make with default config uses auto-generated nodeId") {
        for
          lock     <- InMemoryFSMInstanceLock.make[String]
          strategy <- DistributedLockingStrategy.make(lock)
          result   <- strategy.withLock("instance-1", ZIO.succeed(123))
        yield assertTrue(result == 123)
      },
      test("make with custom config uses provided config") {
        for
          lock <- InMemoryFSMInstanceLock.make[String]
          config = LockConfig.withNodeId(
            nodeId = "test-node",
            lockDuration = Duration.fromSeconds(5),
            acquireTimeout = Duration.fromSeconds(2),
            retryInterval = Duration.fromMillis(100),
          )
          strategy = DistributedLockingStrategy.make(lock, config)
          result <- strategy.withLock("instance-1", ZIO.succeed(456))
        yield assertTrue(result == 456)
      },
      test("layer provides strategy from FSMInstanceLock service") {
        for result <- ZIO
            .serviceWithZIO[LockingStrategy[String]](_.withLock("test", ZIO.succeed(789)))
            .provideLayer(
              ZLayer.fromZIO(InMemoryFSMInstanceLock.make[String]) >>> DistributedLockingStrategy.layer[String]
            )
        yield assertTrue(result == 789)
      },
      test("layer with config uses provided config") {
        val config = LockConfig.withNodeId(
          nodeId = "test-node",
          lockDuration = Duration.fromSeconds(10),
          acquireTimeout = Duration.fromSeconds(5),
          retryInterval = Duration.fromMillis(200),
        )
        for result <- ZIO
            .serviceWithZIO[LockingStrategy[String]](_.withLock("test", ZIO.succeed(999)))
            .provideLayer(
              ZLayer.fromZIO(InMemoryFSMInstanceLock.make[String]) >>> DistributedLockingStrategy.layer[String](config)
            )
        yield assertTrue(result == 999)
      },
      test("withAtomicOperations holds lock during multiple operations") {
        for
          lock     <- InMemoryFSMInstanceLock.make[String]
          strategy <- DistributedLockingStrategy.make(lock)
          results  <- strategy.withAtomicOperations("instance-1") {
            for
              now <- Clock.instant
              // Check lock is held throughout
              held1 <- lock.get("instance-1", now).map(_.isDefined)
              now2  <- Clock.instant
              held2 <- lock.get("instance-1", now2).map(_.isDefined)
            yield (held1, held2)
          }
        yield assertTrue(results._1, results._2)
      },
      test("withAtomicOperations releases lock after completion") {
        for
          lock     <- InMemoryFSMInstanceLock.make[String]
          strategy <- DistributedLockingStrategy.make(lock)
          _        <- strategy.withAtomicOperations("instance-1") {
            ZIO.succeed("done")
          }
          now      <- Clock.instant
          lockHeld <- lock.get("instance-1", now)
        yield assertTrue(lockHeld.isEmpty)
      },
      test("layerWithConfig uses both FSMInstanceLock and LockConfig from environment") {
        val customConfig = LockConfig.withNodeId(
          nodeId = "test-node",
          lockDuration = Duration.fromSeconds(15),
          acquireTimeout = Duration.fromSeconds(3),
          retryInterval = Duration.fromMillis(50),
        )
        for result <- ZIO
            .serviceWithZIO[LockingStrategy[String]](_.withLock("test", ZIO.succeed(888)))
            .provideLayer(
              (ZLayer.fromZIO(InMemoryFSMInstanceLock.make[String]) ++ ZLayer.succeed(customConfig)) >>>
                DistributedLockingStrategy.layerWithConfig[String]
            )
        yield assertTrue(result == 888)
      },
      test("withAtomicOperations with custom heartbeat config") {
        for
          lock     <- InMemoryFSMInstanceLock.make[String]
          strategy <- DistributedLockingStrategy.make(lock)
          heartbeat = LockHeartbeatConfig(
            renewalInterval = Duration.fromSeconds(5),
            renewalDuration = Duration.fromSeconds(15),
          )
          result <- strategy.withAtomicOperations("instance-1", heartbeat) {
            ZIO.succeed("with-heartbeat")
          }
        yield assertTrue(result == "with-heartbeat")
      },
    ),
    suite("DistributedLockingStrategy validation edge cases")(
      test("withLock propagates MechanoidError from effect") {
        for
          lock     <- InMemoryFSMInstanceLock.make[String]
          strategy <- DistributedLockingStrategy.make(lock)
          result   <- strategy.withLock("instance-1", ZIO.fail(FSMStoppedError(Some("test")))).either
        yield result match
          case Left(_: FSMStoppedError) => assertTrue(true)
          case _                        => assertTrue(false)
      },
      test("validation fails when lock held by another node") {
        // This tests the validateLockIfConfigured path where lock is held by different node
        for
          lock <- InMemoryFSMInstanceLock.make[String]
          now  <- Clock.instant
          // Pre-acquire lock with a different node
          _ <- lock.tryAcquire("instance-1", "other-node", Duration.fromSeconds(60), now)
          // Use config with validation enabled but short timeout
          config = LockConfig
            .withNodeId("test-node")
            .withValidateBeforeOperation(true)
            .withAcquireTimeout(Duration.fromMillis(50))
          strategy = DistributedLockingStrategy.make(lock, config)
          fiber  <- strategy.withLock("instance-1", ZIO.succeed("should not reach")).either.fork
          _      <- TestClock.adjust(Duration.fromMillis(100))
          result <- fiber.join
        yield result match
          case Left(_: LockingError) => assertTrue(true)
          case _                     => assertTrue(false)
      },
      test("validation passes when lock held by same node") {
        for
          lock <- InMemoryFSMInstanceLock.make[String]
          config   = LockConfig.withNodeId("test-node").withValidateBeforeOperation(true)
          strategy = DistributedLockingStrategy.make(lock, config)
          result <- strategy.withLock("instance-1", ZIO.succeed("success"))
        yield assertTrue(result == "success")
      },
      test("validation fails with LockTimeout when lock is released during effect") {
        // This tests the case None path in validateLockIfConfigured
        // We need a mock that runs the effect but returns None from get (lock expired/released)
        for
          now <- Clock.instant
          mockLock = new FSMInstanceLock[String]:
            def tryAcquire(id: String, nodeId: String, d: Duration, n: java.time.Instant) =
              ZIO.succeed(LockResult.Acquired(LockToken(id, nodeId, now, now.plusSeconds(60))))
            def acquire(id: String, nodeId: String, d: Duration, t: Duration) =
              ZIO.succeed(LockResult.Acquired(LockToken(id, nodeId, now, now.plusSeconds(60))))
            def release(token: LockToken[String])                                   = ZIO.succeed(true)
            def extend(token: LockToken[String], d: Duration, n: java.time.Instant) = ZIO.succeed(None)
            def get(id: String, n: java.time.Instant) = ZIO.succeed(None) // Lock released/expired
          config   = LockConfig.withNodeId("test-node").withValidateBeforeOperation(true)
          strategy = DistributedLockingStrategy.make(mockLock, config)
          result <- strategy.withLock("instance-1", ZIO.succeed("should not reach")).either
        yield result match
          case Left(err: LockingError) => assertTrue(err.cause.isInstanceOf[LockError.LockTimeout])
          case _                       => assertTrue(false)
      },
      test("validation returns LockBusy when lock owner changed to different node") {
        // This tests the case Some(token) where token.nodeId != config.nodeId
        for
          now <- Clock.instant
          mockLock = new FSMInstanceLock[String]:
            def tryAcquire(id: String, nodeId: String, d: Duration, n: java.time.Instant) =
              ZIO.succeed(LockResult.Acquired(LockToken(id, nodeId, now, now.plusSeconds(60))))
            def acquire(id: String, nodeId: String, d: Duration, t: Duration) =
              ZIO.succeed(LockResult.Acquired(LockToken(id, nodeId, now, now.plusSeconds(60))))
            def release(token: LockToken[String])                                   = ZIO.succeed(true)
            def extend(token: LockToken[String], d: Duration, n: java.time.Instant) = ZIO.succeed(None)
            // Returns lock held by different node
            def get(id: String, n: java.time.Instant) =
              ZIO.succeed(Some(LockToken("instance-1", "other-node", now, now.plusSeconds(60))))
          config   = LockConfig.withNodeId("test-node").withValidateBeforeOperation(true)
          strategy = DistributedLockingStrategy.make(mockLock, config)
          result <- strategy.withLock("instance-1", ZIO.succeed("should not reach")).either
        yield result match
          case Left(err: LockingError) => assertTrue(err.cause.isInstanceOf[LockError.LockBusy])
          case _                       => assertTrue(false)
      },
      test("mapError converts MechanoidError from lock.get to LockAcquisitionFailed") {
        // Test the MechanoidError -> LockAcquisitionFailed mapping in validateLockIfConfigured
        for
          now <- Clock.instant
          mockLock = new FSMInstanceLock[String]:
            def tryAcquire(id: String, nodeId: String, d: Duration, n: java.time.Instant) =
              ZIO.succeed(LockResult.Acquired(LockToken(id, nodeId, now, now.plusSeconds(60))))
            def acquire(id: String, nodeId: String, d: Duration, t: Duration) =
              ZIO.succeed(LockResult.Acquired(LockToken(id, nodeId, now, now.plusSeconds(60))))
            def release(token: LockToken[String])                                   = ZIO.succeed(true)
            def extend(token: LockToken[String], d: Duration, n: java.time.Instant) = ZIO.succeed(None)
            // Fails with MechanoidError
            def get(id: String, n: java.time.Instant) =
              ZIO.fail(mechanoid.core.PersistenceError("Database connection failed"))
          config   = LockConfig.withNodeId("test-node").withValidateBeforeOperation(true)
          strategy = DistributedLockingStrategy.make(mockLock, config)
          result <- strategy.withLock("instance-1", ZIO.succeed("should not reach")).either
        yield result match
          case Left(err: LockingError) => assertTrue(err.cause.isInstanceOf[LockError.LockAcquisitionFailed])
          case _                       => assertTrue(false)
      },
      test("withLock maps LockError from underlying lock to LockingError") {
        // Test the LockError -> LockingError mapping in withLock
        for
          now <- Clock.instant
          mockLock = new FSMInstanceLock[String]:
            def tryAcquire(id: String, nodeId: String, d: Duration, n: java.time.Instant) =
              ZIO.succeed(LockResult.Acquired(LockToken(id, nodeId, now, now.plusSeconds(60))))
            def acquire(id: String, nodeId: String, d: Duration, t: Duration) =
              ZIO.succeed(LockResult.Acquired(LockToken(id, nodeId, now, now.plusSeconds(60))))
            def release(token: LockToken[String])                                   = ZIO.succeed(true)
            def extend(token: LockToken[String], d: Duration, n: java.time.Instant) = ZIO.succeed(None)
            def get(id: String, n: java.time.Instant)                               = ZIO.succeed(None)
            // Override withLock to directly fail with LockError
            override def withLock[R, E, A](id: String, nodeId: String, d: Duration, t: Option[Duration])(
                effect: ZIO[R, E, A]
            ): ZIO[R, E | LockError, A] =
              ZIO.fail(LockError.LockTimeout("instance-1", Duration.fromSeconds(30)))
          config   = LockConfig.withNodeId("test-node").withValidateBeforeOperation(false) // Skip validation
          strategy = DistributedLockingStrategy.make(mockLock, config)
          result <- strategy.withLock("instance-1", ZIO.succeed("should not reach")).either
        yield result match
          case Left(err: LockingError) => assertTrue(err.cause.isInstanceOf[LockError.LockTimeout])
          case _                       => assertTrue(false)
      },
    ),
    suite("LockingStrategy companion")(
      test("optimistic returns OptimisticLockingStrategy layer") {
        for result <- ZIO
            .serviceWithZIO[LockingStrategy[String]](_.withLock("test", ZIO.succeed(42)))
            .provideLayer(LockingStrategy.optimistic[String])
        yield assertTrue(result == 42)
      },
      test("distributed returns DistributedLockingStrategy layer") {
        for result <- ZIO
            .serviceWithZIO[LockingStrategy[String]](_.withLock("test", ZIO.succeed(42)))
            .provideLayer(
              ZLayer.fromZIO(InMemoryFSMInstanceLock.make[String]) >>> LockingStrategy.distributed[String]
            )
        yield assertTrue(result == 42)
      },
      test("distributed with config uses provided config") {
        val config = LockConfig.withNodeId("test-node").withLockDuration(Duration.fromSeconds(5))
        for result <- ZIO
            .serviceWithZIO[LockingStrategy[String]](_.withLock("test", ZIO.succeed(42)))
            .provideLayer(
              ZLayer.fromZIO(InMemoryFSMInstanceLock.make[String]) >>> LockingStrategy.distributed[String](config)
            )
        yield assertTrue(result == 42)
      },
      test("withLock service accessor accesses strategy from environment") {
        for result <- LockingStrategy
            .withLock[String, Any, MechanoidError, Int]("instance-1", ZIO.succeed(42))
            .provideLayer(LockingStrategy.optimistic[String])
        yield assertTrue(result == 42)
      },
    ),
  ) @@ TestAspect.sequential @@ TestAspect.timeout(Duration.fromSeconds(30))
end LockingStrategySpec
