package mechanoid.persistence.timeout

import zio.*
import zio.stream.*
import mechanoid.core.MechanoidError

/** Leader election service for single-active-sweeper mode.
  *
  * Uses lease-based leadership with automatic renewal. When enabled, only the leader node performs timeout sweeps,
  * reducing database load.
  *
  * ==Leadership Lifecycle==
  *
  * {{{
  * [Start] → [Try Acquire] → [Leader?]
  *                              │
  *              ┌───────────────┴───────────────┐
  *              ▼                               ▼
  *         [Renew Loop]                    [Wait for Expiry]
  *              │                               │
  *              ▼                               ▼
  *         [Renewal Failed?] ───────────► [Try Acquire]
  *              │
  *              ▼
  *         [Continue as Leader]
  * }}}
  *
  * ==Usage==
  *
  * {{{
  * ZIO.scoped {
  *   for
  *     leader <- LeaderElection.make(config, nodeId, leaseStore)
  *     _ <- leader.leadershipChanges.foreach { isLeader =>
  *       ZIO.logInfo(s"Leadership changed: \$isLeader")
  *     }.fork
  *     // ... use leader.isLeader in sweeper
  *   yield ()
  * }
  * }}}
  */
trait LeaderElection:
  /** Check if this node is currently the leader. */
  def isLeader: UIO[Boolean]

  /** Stream that emits true when leadership is acquired, false when lost. */
  def leadershipChanges: ZStream[Any, Nothing, Boolean]

  /** Gracefully release leadership if held.
    *
    * Call this during shutdown to allow faster leader election.
    */
  def resign: UIO[Unit]
end LeaderElection

object LeaderElection:

  /** Create a leader election service.
    *
    * Starts a background fiber that:
    *   1. Attempts to acquire the lease
    *   2. If acquired, renews periodically
    *   3. If lost or failed, waits and retries
    *
    * The service is scoped - the background fiber is interrupted when the scope closes.
    *
    * @param config
    *   Leader election configuration
    * @param nodeId
    *   Unique identifier for this node
    * @param store
    *   Lease storage implementation
    * @return
    *   A scoped LeaderElection service
    */
  def make(
      config: LeaderElectionConfig,
      nodeId: String,
      store: LeaseStore,
  ): ZIO[Scope, MechanoidError, LeaderElection] =
    for
      isLeaderRef <- Ref.make(false)
      hub         <- Hub.bounded[Boolean](16)
      _           <- runLeadershipLoop(config, nodeId, store, isLeaderRef, hub).forkScoped
    yield new LeaderElection:
      def isLeader: UIO[Boolean] = isLeaderRef.get

      def leadershipChanges: ZStream[Any, Nothing, Boolean] =
        ZStream.fromHub(hub)

      def resign: UIO[Unit] =
        isLeaderRef.get.flatMap { wasLeader =>
          ZIO
            .when(wasLeader)(
              store.release(config.leaseKey, nodeId).ignore *>
                isLeaderRef.set(false) *>
                hub.publish(false).unit
            )
            .unit
        }

  private def runLeadershipLoop(
      config: LeaderElectionConfig,
      nodeId: String,
      store: LeaseStore,
      isLeaderRef: Ref[Boolean],
      hub: Hub[Boolean],
  ): ZIO[Any, Nothing, Nothing] =
    val attemptLeadership: ZIO[Any, Nothing, Duration] =
      for
        now       <- Clock.instant
        wasLeader <- isLeaderRef.get
        result    <-
          if wasLeader then
            // Try to renew
            store
              .renew(config.leaseKey, nodeId, config.leaseDuration, now)
              .catchAll(_ => ZIO.succeed(false))
          else
            // Try to acquire
            store
              .tryAcquire(config.leaseKey, nodeId, config.leaseDuration, now)
              .map(_.isDefined)
              .catchAll(_ => ZIO.succeed(false))

        // Update state if changed
        _ <- ZIO.when(result != wasLeader)(
          isLeaderRef.set(result) *>
            hub.publish(result).unit *>
            ZIO.logInfo(
              if result then s"Node $nodeId acquired leadership"
              else s"Node $nodeId lost leadership"
            )
        )
      yield
        // Calculate wait time: leader renews frequently, non-leader waits for potential expiry
        if result then config.renewalInterval else config.leaseDuration

    // Schedule that uses the output (Duration) to determine next delay
    (attemptLeadership.flatMap(ZIO.sleep(_))).forever
  end runLeadershipLoop

  /** Create a "always leader" implementation for single-node deployments.
    *
    * This is a no-op implementation that always reports leadership. Useful for testing or when leader election is not
    * needed.
    */
  def alwaysLeader: UIO[LeaderElection] =
    ZIO.succeed(new LeaderElection:
      def isLeader: UIO[Boolean]                            = ZIO.succeed(true)
      def leadershipChanges: ZStream[Any, Nothing, Boolean] =
        ZStream.succeed(true)
      def resign: UIO[Unit] = ZIO.unit)

  /** Create a "never leader" implementation for testing.
    *
    * This implementation never becomes leader. Useful for testing the non-leader code path.
    */
  def neverLeader: UIO[LeaderElection] =
    ZIO.succeed(new LeaderElection:
      def isLeader: UIO[Boolean]                            = ZIO.succeed(false)
      def leadershipChanges: ZStream[Any, Nothing, Boolean] =
        ZStream.succeed(false)
      def resign: UIO[Unit] = ZIO.unit)
end LeaderElection
