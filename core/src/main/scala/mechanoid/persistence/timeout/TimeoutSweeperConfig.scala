package mechanoid.persistence.timeout

import zio.Duration

/** Configuration for the [[TimeoutSweeper]].
  *
  * Uses immutable builder pattern consistent with [[mechanoid.dsl.FSMDefinition]].
  *
  * ==Jitter Algorithm==
  *
  * To prevent thundering herd when multiple sweepers start simultaneously:
  * {{{
  * actualWait = sweepInterval + random(0, jitterFactor * sweepInterval) + backoffOnEmpty
  * }}}
  *
  * ==Usage==
  * {{{
  * val config = TimeoutSweeperConfig()
  *   .withSweepInterval(5.seconds)
  *   .withJitterFactor(0.2)
  *   .withBatchSize(100)
  *   .withClaimDuration(30.seconds)
  *   .withBackoffOnEmpty(10.seconds)
  *   .withLeaderElection(
  *     LeaderElectionConfig()
  *       .withLeaseDuration(30.seconds)
  *       .withRenewalInterval(10.seconds)
  *   )
  * }}}
  *
  * ==Configuration Parameters==
  *
  *   - '''sweepInterval''': Base time between sweeps. Actual wait includes jitter.
  *   - '''jitterFactor''': Randomness factor (0.0 to 1.0). Higher values spread sweeps more.
  *   - '''batchSize''': Max timeouts to process per sweep. Prevents long-running sweeps.
  *   - '''claimDuration''': How long a claim lasts. Should exceed expected processing time.
  *   - '''backoffOnEmpty''': Extra wait when no timeouts found. Reduces polling frequency.
  *   - '''leaderElection''': Optional config for single-active-sweeper mode.
  *   - '''nodeId''': Unique identifier for this node. Auto-generated if not provided.
  *
  * @param sweepInterval
  *   Base interval between sweeps
  * @param jitterFactor
  *   Randomness factor (0.0 to 1.0)
  * @param batchSize
  *   Maximum timeouts to process per sweep
  * @param claimDuration
  *   How long to hold a claim
  * @param backoffOnEmpty
  *   Optional extra wait when no timeouts found
  * @param leaderElection
  *   Optional leader election configuration
  * @param nodeId
  *   Unique identifier for this sweeper node
  */
final case class TimeoutSweeperConfig private (
    sweepInterval: Duration,
    jitterFactor: Double,
    batchSize: Int,
    claimDuration: Duration,
    backoffOnEmpty: Option[Duration],
    leaderElection: Option[LeaderElectionConfig],
    nodeId: String,
):
  require(
    jitterFactor >= 0.0 && jitterFactor <= 1.0,
    s"jitterFactor must be in [0.0, 1.0], got: $jitterFactor",
  )
  require(batchSize > 0, s"batchSize must be positive, got: $batchSize")
  require(
    claimDuration.toMillis > 0,
    s"claimDuration must be positive, got: $claimDuration",
  )

  def withSweepInterval(d: Duration): TimeoutSweeperConfig =
    copy(sweepInterval = d)

  def withJitterFactor(f: Double): TimeoutSweeperConfig =
    copy(jitterFactor = f)

  def withBatchSize(n: Int): TimeoutSweeperConfig =
    copy(batchSize = n)

  def withClaimDuration(d: Duration): TimeoutSweeperConfig =
    copy(claimDuration = d)

  def withBackoffOnEmpty(d: Duration): TimeoutSweeperConfig =
    copy(backoffOnEmpty = Some(d))

  def withoutBackoffOnEmpty: TimeoutSweeperConfig =
    copy(backoffOnEmpty = None)

  def withLeaderElection(config: LeaderElectionConfig): TimeoutSweeperConfig =
    copy(leaderElection = Some(config))

  def withoutLeaderElection: TimeoutSweeperConfig =
    copy(leaderElection = None)

  def withNodeId(id: String): TimeoutSweeperConfig =
    copy(nodeId = id)
end TimeoutSweeperConfig

object TimeoutSweeperConfig:
  /** Create a configuration with sensible defaults.
    *
    * Defaults:
    *   - sweepInterval: 5 seconds
    *   - jitterFactor: 0.2 (20% randomness)
    *   - batchSize: 100
    *   - claimDuration: 30 seconds
    *   - backoffOnEmpty: None
    *   - leaderElection: None (multi-sweeper mode)
    *   - nodeId: random UUID
    */
  def apply(): TimeoutSweeperConfig =
    TimeoutSweeperConfig(
      sweepInterval = Duration.fromSeconds(5),
      jitterFactor = 0.2,
      batchSize = 100,
      claimDuration = Duration.fromSeconds(30),
      backoffOnEmpty = None,
      leaderElection = None,
      // Portable across JVM and Scala.js (avoid java.util.UUID.randomUUID / SecureRandom).
      nodeId = scala.util.Random.alphanumeric.take(16).mkString,
    )
end TimeoutSweeperConfig

/** Configuration for leader election in single-active-sweeper mode.
  *
  * When enabled, only the leader node performs sweeps. This reduces database load at the cost of slightly longer
  * failover time if the leader crashes.
  *
  * ==How It Works==
  *
  * Leader election uses a lease-based approach:
  *
  *   1. Nodes compete to acquire a lease (write to database with expiry)
  *   2. The winner becomes leader and starts sweeping
  *   3. Leader must renew the lease periodically (every `renewalInterval`)
  *   4. If renewal fails (node crash, network partition), lease expires
  *   5. Other nodes detect expiry and compete to become new leader
  *
  * ==Timing Constraints==
  *
  * '''IMPORTANT''': `renewalInterval` must be less than `leaseDuration`. The difference is the "grace period" for
  * network hiccups:
  *
  * {{{
  * gracePeriod = leaseDuration - renewalInterval
  * }}}
  *
  * Recommended: `leaseDuration = 3 * renewalInterval`
  *
  * ==Usage==
  * {{{
  * LeaderElectionConfig()
  *   .withLeaseDuration(30.seconds)
  *   .withRenewalInterval(10.seconds)
  *   .withLeaseKey("my-app-timeout-leader")
  * }}}
  *
  * @param leaseDuration
  *   How long a lease is valid (must be > renewalInterval)
  * @param renewalInterval
  *   How often to renew the lease
  * @param leaseKey
  *   The key/name for this leader election (for namespacing)
  */
final case class LeaderElectionConfig private (
    leaseDuration: Duration,
    renewalInterval: Duration,
    leaseKey: String,
):
  require(
    renewalInterval.toMillis < leaseDuration.toMillis,
    s"renewalInterval ($renewalInterval) must be less than leaseDuration ($leaseDuration)",
  )
  require(
    renewalInterval.toMillis > 0,
    s"renewalInterval must be positive, got: $renewalInterval",
  )

  def withLeaseDuration(d: Duration): LeaderElectionConfig =
    copy(leaseDuration = d)

  def withRenewalInterval(d: Duration): LeaderElectionConfig =
    copy(renewalInterval = d)

  def withLeaseKey(key: String): LeaderElectionConfig =
    copy(leaseKey = key)
end LeaderElectionConfig

object LeaderElectionConfig:
  /** Create a configuration with sensible defaults.
    *
    * Defaults:
    *   - leaseDuration: 30 seconds
    *   - renewalInterval: 10 seconds (3x safety margin)
    *   - leaseKey: "mechanoid-timeout-leader"
    */
  def apply(): LeaderElectionConfig =
    LeaderElectionConfig(
      leaseDuration = Duration.fromSeconds(30),
      renewalInterval = Duration.fromSeconds(10),
      leaseKey = "mechanoid-timeout-leader",
    )
end LeaderElectionConfig
