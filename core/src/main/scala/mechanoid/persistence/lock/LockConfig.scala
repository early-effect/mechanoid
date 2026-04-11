package mechanoid.persistence.lock

import zio.{Duration, UIO, ULayer, ZIO, ZLayer}

/** Configuration for FSM instance locking.
  *
  * ==Resilience to Node Failures==
  *
  * The locking mechanism uses lease-based locks that automatically expire. This handles several failure scenarios:
  *
  *   1. '''Node crash''': Lock expires after `lockDuration`, other nodes can proceed
  *   2. '''Network partition''': Same as crash - lock expires
  *   3. '''Long GC pause''': If pause exceeds `lockDuration`, lock expires
  *
  * ==Zombie Node Protection==
  *
  * A "zombie" scenario occurs when:
  *   1. Node A acquires lock
  *   2. Node A pauses (GC, network issue)
  *   3. Lock expires
  *   4. Node B acquires lock, processes events
  *   5. Node A wakes up, thinks it still has the lock
  *
  * Protection is provided by two mechanisms:
  *
  *   - '''Lock validation''': Before each operation, check if lock is still valid
  *   - '''EventStore optimistic locking''': Even if zombie writes, sequence conflict detected
  *
  * ==Choosing Lock Duration==
  *
  * The `lockDuration` should be:
  *   - Long enough to complete normal operations (with margin for GC pauses)
  *   - Short enough that failures don't block the system too long
  *
  * Typical values:
  *   - Fast operations: 10-30 seconds
  *   - Complex operations: 1-5 minutes
  *   - Background jobs: Consider using `extend()` for heartbeating
  *
  * @param lockDuration
  *   How long to hold locks (default: 30 seconds)
  * @param acquireTimeout
  *   Maximum time to wait when acquiring (default: 10 seconds)
  * @param retryInterval
  *   How often to retry when lock is busy (default: 100ms)
  * @param validateBeforeOperation
  *   Whether to check lock validity before each operation
  * @param nodeId
  *   Unique identifier for this node (auto-generated if not set)
  */
final case class LockConfig private (
    lockDuration: Duration,
    acquireTimeout: Duration,
    retryInterval: Duration,
    validateBeforeOperation: Boolean,
    nodeId: String,
):
  require(
    lockDuration.toMillis > 0,
    "lockDuration must be positive",
  )
  require(
    acquireTimeout.toMillis > 0,
    "acquireTimeout must be positive",
  )
  require(
    retryInterval.toMillis > 0,
    "retryInterval must be positive",
  )
  require(
    lockDuration.toMillis > retryInterval.toMillis,
    "lockDuration must be greater than retryInterval",
  )

  def withLockDuration(duration: Duration): LockConfig =
    copy(lockDuration = duration)

  def withAcquireTimeout(timeout: Duration): LockConfig =
    copy(acquireTimeout = timeout)

  def withRetryInterval(interval: Duration): LockConfig =
    copy(retryInterval = interval)

  def withValidateBeforeOperation(validate: Boolean): LockConfig =
    copy(validateBeforeOperation = validate)

  def withNodeId(id: String): LockConfig =
    copy(nodeId = id)
end LockConfig

object LockConfig:

  /** Generate a unique node ID using hostname and random suffix. */
  def generateNodeId: UIO[String] =
    for
      hostname <- ZIO.attempt(java.net.InetAddress.getLocalHost.getHostName).orElseSucceed("unknown")
      suffix   <- ZIO.succeed(java.util.UUID.randomUUID().toString.take(8))
    yield s"$hostname-$suffix"

  /** Create a LockConfig with default settings and auto-generated nodeId. */
  def make(
      lockDuration: Duration = Duration.fromSeconds(30),
      acquireTimeout: Duration = Duration.fromSeconds(10),
      retryInterval: Duration = Duration.fromMillis(100),
      validateBeforeOperation: Boolean = true,
  ): UIO[LockConfig] =
    generateNodeId.map(nodeId =>
      LockConfig(lockDuration, acquireTimeout, retryInterval, validateBeforeOperation, nodeId)
    )

  /** Create a LockConfig with a specific nodeId (no ZIO effect needed). */
  def withNodeId(
      nodeId: String,
      lockDuration: Duration = Duration.fromSeconds(30),
      acquireTimeout: Duration = Duration.fromSeconds(10),
      retryInterval: Duration = Duration.fromMillis(100),
      validateBeforeOperation: Boolean = true,
  ): LockConfig =
    LockConfig(lockDuration, acquireTimeout, retryInterval, validateBeforeOperation, nodeId)

  /** Default configuration suitable for most use cases. */
  val default: UIO[LockConfig] = make()

  /** Configuration for fast operations with short lock duration. */
  val fast: UIO[LockConfig] = make(
    lockDuration = Duration.fromSeconds(10),
    acquireTimeout = Duration.fromSeconds(5),
  )

  /** Configuration for long-running operations. */
  val longRunning: UIO[LockConfig] = make(
    lockDuration = Duration.fromSeconds(300),
    acquireTimeout = Duration.fromSeconds(30),
  )

  // ============================================
  // ZLayer helpers for environment-based config
  // ============================================

  /** Layer providing the default lock configuration. */
  val defaultLayer: ULayer[LockConfig] = ZLayer.fromZIO(default)

  /** Layer providing the fast lock configuration. */
  val fastLayer: ULayer[LockConfig] = ZLayer.fromZIO(fast)

  /** Layer providing the long-running lock configuration. */
  val longRunningLayer: ULayer[LockConfig] = ZLayer.fromZIO(longRunning)

  /** Create a layer from a custom configuration.
    *
    * @param config
    *   The lock configuration to provide
    * @return
    *   A layer that provides the configuration
    */
  def layer(config: LockConfig): ULayer[LockConfig] = ZLayer.succeed(config)
end LockConfig
