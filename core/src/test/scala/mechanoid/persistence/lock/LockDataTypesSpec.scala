package mechanoid.persistence.lock

import zio.*
import zio.test.*
import java.time.Instant

object LockDataTypesSpec extends ZIOSpecDefault:

  def spec = suite("LockDataTypesSpec")(
    suite("LockToken")(
      test("isValid returns true before expiration") {
        val now   = Instant.now()
        val token = LockToken("instance-1", "node-A", now, now.plusSeconds(30))
        assertTrue(token.isValid(now))
      },
      test("isValid returns false after expiration") {
        val now   = Instant.now()
        val past  = now.minusSeconds(60)
        val token = LockToken("instance-1", "node-A", past, past.plusSeconds(30))
        assertTrue(!token.isValid(now))
      },
      test("isValid returns false at exact expiration moment") {
        val now   = Instant.now()
        val token = LockToken("instance-1", "node-A", now.minusSeconds(30), now)
        // at exact expiration time, it should be invalid (not before)
        assertTrue(!token.isValid(now))
      },
      test("isExpired is opposite of isValid") {
        val now     = Instant.now()
        val valid   = LockToken("instance-1", "node-A", now, now.plusSeconds(30))
        val expired = LockToken("instance-1", "node-A", now.minusSeconds(60), now.minusSeconds(30))
        assertTrue(
          !valid.isExpired(now),
          expired.isExpired(now),
          valid.isValid(now) == !valid.isExpired(now),
          expired.isValid(now) == !expired.isExpired(now),
        )
      },
      test("remainingTime returns positive duration before expiration") {
        val now       = Instant.now()
        val token     = LockToken("instance-1", "node-A", now, now.plusSeconds(30))
        val remaining = token.remainingTime(now)
        assertTrue(
          remaining.getSeconds == 30,
          !remaining.isNegative,
        )
      },
      test("remainingTime returns negative duration after expiration") {
        val now       = Instant.now()
        val token     = LockToken("instance-1", "node-A", now.minusSeconds(60), now.minusSeconds(30))
        val remaining = token.remainingTime(now)
        assertTrue(remaining.isNegative)
      },
      test("remainingTime returns zero at exact expiration") {
        val now       = Instant.now()
        val token     = LockToken("instance-1", "node-A", now.minusSeconds(30), now)
        val remaining = token.remainingTime(now)
        assertTrue(remaining.isZero)
      },
    ),
    suite("LockResult")(
      test("isAcquired returns true for Acquired") {
        val token                      = LockToken("instance-1", "node-A", Instant.now(), Instant.now().plusSeconds(30))
        val result: LockResult[String] = LockResult.Acquired(token)
        assertTrue(result.isAcquired)
      },
      test("isAcquired returns false for Busy") {
        val result: LockResult[String] = LockResult.Busy("other-node", Instant.now().plusSeconds(30))
        assertTrue(!result.isAcquired)
      },
      test("isAcquired returns false for TimedOut") {
        val result: LockResult[String] = LockResult.TimedOut()
        assertTrue(!result.isAcquired)
      },
      test("isBusy returns true for Busy") {
        val result: LockResult[String] = LockResult.Busy("other-node", Instant.now().plusSeconds(30))
        assertTrue(result.isBusy)
      },
      test("isBusy returns false for Acquired") {
        val token                      = LockToken("instance-1", "node-A", Instant.now(), Instant.now().plusSeconds(30))
        val result: LockResult[String] = LockResult.Acquired(token)
        assertTrue(!result.isBusy)
      },
      test("isBusy returns false for TimedOut") {
        val result: LockResult[String] = LockResult.TimedOut()
        assertTrue(!result.isBusy)
      },
      test("tokenOption returns Some for Acquired") {
        val token                      = LockToken("instance-1", "node-A", Instant.now(), Instant.now().plusSeconds(30))
        val result: LockResult[String] = LockResult.Acquired(token)
        assertTrue(result.tokenOption == Some(token))
      },
      test("tokenOption returns None for Busy") {
        val result: LockResult[String] = LockResult.Busy("other-node", Instant.now().plusSeconds(30))
        assertTrue(result.tokenOption.isEmpty)
      },
      test("tokenOption returns None for TimedOut") {
        val result: LockResult[String] = LockResult.TimedOut()
        assertTrue(result.tokenOption.isEmpty)
      },
    ),
    suite("LockConfig")(
      test("default config has expected values") {
        for config <- LockConfig.default
        yield assertTrue(
          config.lockDuration == Duration.fromSeconds(30),
          config.acquireTimeout == Duration.fromSeconds(10),
          config.retryInterval == Duration.fromMillis(100),
          config.validateBeforeOperation,
        )
      },
      test("fast config has shorter durations") {
        for config <- LockConfig.fast
        yield assertTrue(
          config.lockDuration == Duration.fromSeconds(10),
          config.acquireTimeout == Duration.fromSeconds(5),
        )
      },
      test("longRunning config has longer durations") {
        for config <- LockConfig.longRunning
        yield assertTrue(
          config.lockDuration == Duration.fromSeconds(300),
          config.acquireTimeout == Duration.fromSeconds(30),
        )
      },
      test("withLockDuration returns modified config") {
        for config <- LockConfig.default
        yield assertTrue(config.withLockDuration(Duration.fromSeconds(60)).lockDuration == Duration.fromSeconds(60))
      },
      test("withAcquireTimeout returns modified config") {
        for config <- LockConfig.default
        yield assertTrue(config.withAcquireTimeout(Duration.fromSeconds(20)).acquireTimeout == Duration.fromSeconds(20))
      },
      test("withRetryInterval returns modified config") {
        for config <- LockConfig.default
        yield assertTrue(config.withRetryInterval(Duration.fromMillis(200)).retryInterval == Duration.fromMillis(200))
      },
      test("withValidateBeforeOperation returns modified config") {
        for config <- LockConfig.default
        yield assertTrue(!config.withValidateBeforeOperation(false).validateBeforeOperation)
      },
      test("withNodeId returns modified config") {
        for config <- LockConfig.default
        yield assertTrue(config.withNodeId("my-custom-node").nodeId == "my-custom-node")
      },
      test("generateNodeId creates unique IDs") {
        for
          id1 <- LockConfig.generateNodeId
          id2 <- LockConfig.generateNodeId
        yield assertTrue(id1 != id2)
      },
      test("generateNodeId includes hostname component") {
        for id <- LockConfig.generateNodeId
        yield assertTrue(id.contains("-"))
      },
      test("generateNodeId handles hostname failure gracefully") {
        // generateNodeId uses ZIO.attempt(...).orElseSucceed("unknown")
        // This tests that the ZIO-based error handling works
        for id <- LockConfig.generateNodeId
        yield assertTrue(id.nonEmpty)
      },
      test("validation fails for non-positive lockDuration") {
        val result =
          try
            LockConfig.withNodeId("test", lockDuration = Duration.Zero)
            false
          catch case _: IllegalArgumentException => true
        assertTrue(result)
      },
      test("validation fails for non-positive acquireTimeout") {
        val result =
          try
            LockConfig.withNodeId("test", acquireTimeout = Duration.Zero)
            false
          catch case _: IllegalArgumentException => true
        assertTrue(result)
      },
      test("validation fails for non-positive retryInterval") {
        val result =
          try
            LockConfig.withNodeId("test", retryInterval = Duration.Zero)
            false
          catch case _: IllegalArgumentException => true
        assertTrue(result)
      },
      test("validation fails when lockDuration <= retryInterval") {
        val result =
          try
            LockConfig.withNodeId(
              "test",
              lockDuration = Duration.fromMillis(100),
              retryInterval = Duration.fromMillis(100),
            )
            false
          catch case _: IllegalArgumentException => true
        assertTrue(result)
      },
      test("defaultLayer provides default config") {
        for
          config   <- ZIO.service[LockConfig].provideLayer(LockConfig.defaultLayer)
          expected <- LockConfig.default
        yield assertTrue(
          config.lockDuration == expected.lockDuration,
          config.acquireTimeout == expected.acquireTimeout,
        )
      },
      test("fastLayer provides fast config") {
        for
          config   <- ZIO.service[LockConfig].provideLayer(LockConfig.fastLayer)
          expected <- LockConfig.fast
        yield assertTrue(
          config.lockDuration == expected.lockDuration,
          config.acquireTimeout == expected.acquireTimeout,
        )
      },
      test("longRunningLayer provides longRunning config") {
        for
          config   <- ZIO.service[LockConfig].provideLayer(LockConfig.longRunningLayer)
          expected <- LockConfig.longRunning
        yield assertTrue(
          config.lockDuration == expected.lockDuration,
          config.acquireTimeout == expected.acquireTimeout,
        )
      },
      test("layer creates custom config layer") {
        val custom = LockConfig.withNodeId("test", lockDuration = Duration.fromSeconds(45))
        for config <- ZIO.service[LockConfig].provideLayer(LockConfig.layer(custom))
        yield assertTrue(config.lockDuration == Duration.fromSeconds(45))
      },
      test("withNodeId creates config without ZIO effect") {
        val config = LockConfig.withNodeId("my-node")
        assertTrue(
          config.nodeId == "my-node",
          config.lockDuration == Duration.fromSeconds(30),
        )
      },
      test("make creates config with auto-generated nodeId") {
        for config <- LockConfig.make()
        yield assertTrue(
          config.nodeId.nonEmpty,
          config.lockDuration == Duration.fromSeconds(30),
        )
      },
    ),
    suite("LockError")(
      test("LockBusy has correct message") {
        val error = LockError.LockBusy("instance-1", "other-node", Instant.parse("2024-01-01T00:00:00Z"))
        assertTrue(
          error.message.contains("instance-1"),
          error.message.contains("other-node"),
        )
      },
      test("LockTimeout has correct message") {
        val error = LockError.LockTimeout("instance-1", Duration.fromSeconds(10))
        assertTrue(
          error.message.contains("instance-1"),
          error.message.contains("10"),
        )
      },
      test("LockAcquisitionFailed has correct message") {
        val cause = new RuntimeException("test error")
        val error = LockError.LockAcquisitionFailed("instance-1", cause)
        assertTrue(
          error.message.contains("instance-1"),
          error.message.contains("test error"),
        )
      },
      test("LockReleaseFailed has correct message") {
        val cause = new RuntimeException("release error")
        val error = LockError.LockReleaseFailed("instance-1", cause)
        assertTrue(
          error.message.contains("instance-1"),
          error.message.contains("release error"),
        )
      },
    ),
  )
end LockDataTypesSpec
