package mechanoid.persistence.timeout

import zio.*
import mechanoid.core.{MechanoidError, PersistenceError}
import mechanoid.runtime.FSMRuntime

/** Metrics emitted by the sweeper for monitoring.
  *
  * @param sweepCount
  *   Total number of sweep iterations completed
  * @param timeoutsFired
  *   Number of timeouts successfully fired
  * @param timeoutsSkipped
  *   Timeouts skipped (state changed, cancelled, etc.)
  * @param claimConflicts
  *   Times another node had already claimed a timeout
  * @param errors
  *   Number of errors encountered during sweeping
  */
final case class SweeperMetrics(
    sweepCount: Long,
    timeoutsFired: Long,
    timeoutsSkipped: Long,
    claimConflicts: Long,
    errors: Long,
)

object SweeperMetrics:
  val empty: SweeperMetrics = SweeperMetrics(0, 0, 0, 0, 0)

/** A background service that sweeps for expired timeouts and fires them.
  *
  * ==Operation Modes==
  *
  * '''Multi-sweeper mode''' (default): Multiple nodes run sweepers concurrently. Coordination via atomic database
  * claims prevents duplicate firing. This is simpler to deploy and naturally load-balanced.
  *
  * '''Single-active mode''' (with leader election): Only the leader sweeps. Reduces database load but requires leader
  * election infrastructure and has slightly longer failover time.
  *
  * ==Jitter Algorithm==
  *
  * To prevent thundering herd when multiple sweepers start simultaneously:
  * {{{
  * actualWait = sweepInterval + random(0, jitterFactor * sweepInterval)
  *            + (noTimeoutsFound ? backoffOnEmpty : 0)
  * }}}
  *
  * ==Claim Flow==
  *
  * {{{
  * [Query Expired] → [For Each Timeout]
  *                         │
  *                    [Claim Atomically]
  *                         │
  *        ┌────────────────┼────────────────┐
  *        ▼                ▼                ▼
  *   [Claimed]      [AlreadyClaimed]   [NotFound]
  *        │                │                │
  *        ▼                │                │
  *   [Fire Timeout]        │                │
  *        │                │                │
  *   ┌────┴────┐           │                │
  *   ▼         ▼           ▼                ▼
  * [Success] [Error]    [Skip]          [Skip]
  *   │         │
  *   ▼         ▼
  * [Complete] [Release]
  * }}}
  *
  * ==Usage==
  *
  * {{{
  * ZIO.scoped {
  *   for
  *     fsm     <- FSMRuntime(id, machine, initialState)
  *     sweeper <- TimeoutSweeper.make(
  *       config = TimeoutSweeperConfig()
  *         .withSweepInterval(5.seconds)
  *         .withJitterFactor(0.2),
  *       timeoutStore = myTimeoutStore,
  *       runtime = fsm
  *     )
  *     _ <- ZIO.never // Keep running until scope closes
  *   yield ()
  * }
  * }}}
  */
trait TimeoutSweeper:
  /** Check if the sweeper is currently running. */
  def isRunning: UIO[Boolean]

  /** Get current metrics for monitoring. */
  def metrics: UIO[SweeperMetrics]

  /** Stop the sweeper gracefully. */
  def stop: UIO[Unit]

/** TimeoutSweeper implementation as a case class with services/config as fields.
  *
  * Uses FSMRuntime directly to fire timeout events. Before firing, validates that the FSM is still in the expected
  * state by comparing `stateHash` and `sequenceNr` from the timeout record with the current FSM state.
  *
  * @tparam Id
  *   FSM instance identifier type
  * @tparam S
  *   State type
  * @tparam E
  *   Event type
  * @param config
  *   Sweeper configuration
  * @param timeoutStore
  *   Storage for scheduled timeouts
  * @param runtime
  *   FSMRuntime to send timeout events to
  * @param leaseStore
  *   Optional lease store for leader election
  */
final case class TimeoutSweeperImpl[Id, S, E](
    config: TimeoutSweeperConfig,
    timeoutStore: TimeoutStore[Id],
    runtime: FSMRuntime[Id, S, E],
    leaseStore: Option[LeaseStore] = None,
):
  /** Look up the timeout event by armed name on the current leaf. */
  def resolveTimeoutEvent(state: S, name: String): Option[E] =
    runtime.timeoutConfigForState(state).find(_.name == name).map(_.event)
end TimeoutSweeperImpl

object TimeoutSweeper:

  /** Create and start a timeout sweeper with FSMRuntime integration.
    *
    * The sweeper starts immediately and runs until the scope closes or `stop` is called. It resolves event hashes back
    * to typed events and fires them via `runtime.send(event)`.
    *
    * @param config
    *   Sweeper configuration (intervals, batch size, jitter, etc.)
    * @param timeoutStore
    *   Storage for scheduled timeouts
    * @param runtime
    *   FSMRuntime to send timeout events to
    * @param leaseStore
    *   Optional lease store for leader election (required if config.leaderElection is set)
    * @return
    *   A scoped TimeoutSweeper service
    */
  def make[Id, S, E](
      config: TimeoutSweeperConfig,
      timeoutStore: TimeoutStore[Id],
      runtime: FSMRuntime[Id, S, E],
      leaseStore: Option[LeaseStore] = None,
  ): ZIO[Scope, MechanoidError, TimeoutSweeper] =
    val impl = TimeoutSweeperImpl(config, timeoutStore, runtime, leaseStore)
    for
      runningRef <- Ref.make(true)
      metricsRef <- Ref.make(SweeperMetrics.empty)

      // Set up leader election if configured
      leaderElection <- config.leaderElection match
        case Some(leConfig) =>
          leaseStore match
            case Some(ls) =>
              LeaderElection.make(leConfig, config.nodeId, ls).map(Some(_))
            case None =>
              ZIO.fail(
                PersistenceError(
                  new IllegalArgumentException(
                    "LeaseStore required when leader election is configured"
                  )
                )
              )
        case None =>
          ZIO.succeed(None)

      // Start the sweep loop
      _ <- runSweepLoop(
        impl,
        runningRef,
        metricsRef,
        leaderElection,
      ).forkScoped
    yield new TimeoutSweeper:
      def isRunning: UIO[Boolean]      = runningRef.get
      def metrics: UIO[SweeperMetrics] = metricsRef.get
      def stop: UIO[Unit]              =
        runningRef.set(false) *>
          leaderElection.fold(ZIO.unit)(_.resign)
    end for
  end make

  private def runSweepLoop[Id, S, E](
      impl: TimeoutSweeperImpl[Id, S, E],
      runningRef: Ref[Boolean],
      metricsRef: Ref[SweeperMetrics],
      leaderElection: Option[LeaderElection],
  ): ZIO[Any, Nothing, Unit] =

    // Sweep effect returns shouldContinue status
    val sweep: ZIO[Any, Nothing, Boolean] =
      for
        // Check if we should sweep (always in multi-mode, only if leader in single-mode)
        shouldSweep <- leaderElection.fold(ZIO.succeed(true))(_.isLeader)
        count       <-
          if shouldSweep then
            performSweep(impl, metricsRef)
              .catchAll { error =>
                metricsRef.update(m => m.copy(errors = m.errors + 1)) *>
                  ZIO.logError(s"Sweep error: $error").as(0)
              }
          else ZIO.succeed(0)
        _ <- metricsRef.update(m => m.copy(sweepCount = m.sweepCount + 1))
        // Add extra backoff delay when no timeouts found (embedded in effect)
        _ <- impl.config.backoffOnEmpty match
          case Some(backoff) if count == 0 => ZIO.sleep(backoff)
          case _                           => ZIO.unit
        shouldContinue <- runningRef.get
      yield shouldContinue

    // Schedule with timing, jitter, and stop condition
    // jittered(min, max) scales delay by random factor in [min, max]
    // e.g., jitterFactor=0.1 → jittered(0.9, 1.1) → ±10% random variation
    val baseSchedule     = Schedule.spaced(impl.config.sweepInterval)
    val jitteredSchedule =
      if impl.config.jitterFactor > 0 then
        baseSchedule.jittered(1.0 - impl.config.jitterFactor, 1.0 + impl.config.jitterFactor)
      else baseSchedule
    val schedule = jitteredSchedule && Schedule.recurWhile[Boolean](identity)

    sweep.repeat(schedule).unit
  end runSweepLoop

  private def performSweep[Id, S, E](
      impl: TimeoutSweeperImpl[Id, S, E],
      metricsRef: Ref[SweeperMetrics],
  ): ZIO[Any, MechanoidError, Int] =
    for
      now     <- Clock.instant
      metrics <- metricsRef.get
      _       <- ZIO.logInfo(s"Sweep #${metrics.sweepCount + 1} starting (nodeId=${impl.config.nodeId})")
      expired <- impl.timeoutStore.queryExpired(impl.config.batchSize, now)
      results <- ZIO.foreach(expired) { timeout =>
        processTimeout(impl, timeout, metricsRef)
      }
      fired = results.count(identity)
      _ <- ZIO.logInfo(
        s"Sweep #${metrics.sweepCount + 1} complete: found=${expired.size}, fired=$fired, skipped=${expired.size - fired}"
      )
    yield fired

  private def processTimeout[Id, S, E](
      impl: TimeoutSweeperImpl[Id, S, E],
      timeout: ScheduledTimeout[Id],
      metricsRef: Ref[SweeperMetrics],
  ): ZIO[Any, MechanoidError, Boolean] =
    for
      now         <- Clock.instant
      claimResult <- impl.timeoutStore.claim(
        timeout.instanceId,
        timeout.name,
        impl.config.nodeId,
        impl.config.claimDuration,
        now,
      )
      fired <- claimResult match
        case ClaimResult.Claimed(_) =>
          for
            currentState <- impl.runtime.currentState
            currentStateHash = impl.runtime.machine.stateEnum.caseHash(currentState)
            stateMatches     = currentStateHash == timeout.stateHash
            eventOpt         = impl.resolveTimeoutEvent(currentState, timeout.name)

            result <-
              if stateMatches then
                eventOpt match
                  case Some(event) =>
                    impl.runtime
                      .send(event)
                      .flatMap { _ =>
                        impl.timeoutStore.complete(timeout.instanceId, timeout.name, timeout.sequenceNr) *>
                          metricsRef.update(m => m.copy(timeoutsFired = m.timeoutsFired + 1))
                      }
                      .catchAll { error =>
                        impl.timeoutStore.release(timeout.instanceId, timeout.name) *>
                          ZIO.logWarning(
                            s"Failed to fire timeout ${timeout.name} for ${timeout.instanceId}: $error"
                          )
                      }
                      .as(true)
                  case None =>
                    ZIO.logWarning(
                      s"No timeout named ${timeout.name} on current leaf for ${timeout.instanceId}"
                    ) *>
                      impl.timeoutStore.complete(timeout.instanceId, timeout.name, timeout.sequenceNr) *>
                      metricsRef.update(m => m.copy(timeoutsSkipped = m.timeoutsSkipped + 1)).as(false)
              else
                ZIO.logDebug(
                  s"Skipping stale timeout ${timeout.name} for ${timeout.instanceId}: " +
                    s"expected stateHash=${timeout.stateHash}, actual=$currentStateHash"
                ) *>
                  impl.timeoutStore.complete(timeout.instanceId, timeout.name, timeout.sequenceNr) *>
                  metricsRef.update(m => m.copy(timeoutsSkipped = m.timeoutsSkipped + 1)).as(false)
          yield result

        case ClaimResult.AlreadyClaimed(_, _) =>
          metricsRef
            .update(m => m.copy(claimConflicts = m.claimConflicts + 1))
            .as(false)

        case ClaimResult.NotFound | ClaimResult.StateChanged(_) =>
          metricsRef
            .update(m => m.copy(timeoutsSkipped = m.timeoutsSkipped + 1))
            .as(false)
    yield fired
end TimeoutSweeper
