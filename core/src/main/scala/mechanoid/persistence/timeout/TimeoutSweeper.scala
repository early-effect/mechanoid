package mechanoid.persistence.timeout

import zio.*
import mechanoid.core.{InstanceMismatchError, InstanceNotFoundError, MechanoidError, PersistenceError}
import mechanoid.runtime.{FSMRuntime, InstanceMailbox}

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
  *   [Open claimed id]     │                │
  *        │                │                │
  *   ┌────┴────┐           │                │
  *   ▼         ▼           ▼                ▼
  * [Success] [Error]    [Skip]          [Skip]
  *   │         │
  *   ▼         ▼
  * [Complete] [Release]
  * }}}
  *
  * Servers are ephemeral. Any node may claim; the claim is what fires once. After claim the sweeper reconstructs a
  * runtime for that row's `instanceId`, sends, and drops it.
  *
  * ==Usage==
  *
  * Heartbeat (one long-lived instance):
  * {{{
  * ZIO.scoped {
  *   for
  *     fsm     <- FSMRuntime(id, machine, initialState)
  *     sweeper <- TimeoutSweeper.pinned(
  *       config = TimeoutSweeperConfig()
  *         .withSweepInterval(5.seconds)
  *         .withJitterFactor(0.2),
  *       timeoutStore = myTimeoutStore,
  *       runtime = fsm
  *     )
  *     _ <- ZIO.never
  *   yield ()
  * }
  * }}}
  *
  * Load-on-demand (REST / many instances):
  * {{{
  * TimeoutSweeper.make(
  *   config,
  *   timeoutStore,
  *   id => FSMRuntime.existing(id, machine, initialState),
  * )
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
  * After an atomic claim, opens a runtime for that row's `instanceId`, validates `stateHash` / named config on that
  * machine, then `send`s. Servers are ephemeral: any node that wins the claim may fire, and only that claim fires.
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
  * @param open
  *   Reconstruct (or return a pinned runtime) for a claimed instance id
  * @param leaseStore
  *   Optional lease store for leader election
  */
final case class TimeoutSweeperImpl[Id, S, E](
    config: TimeoutSweeperConfig,
    timeoutStore: TimeoutStore[Id],
    open: Id => ZIO[Scope, MechanoidError, FSMRuntime[Id, S, E]],
    mailbox: InstanceMailbox[Id],
    leaseStore: Option[LeaseStore] = None,
    accept: Id => Boolean = (_: Id) => true,
):
  /** Look up the timeout event by armed name on the current leaf. */
  def resolveTimeoutEvent(runtime: FSMRuntime[Id, S, E], state: S, name: String): Option[E] =
    runtime.timeoutConfigForState(state).find(_.name == name).map(_.event)
end TimeoutSweeperImpl

object TimeoutSweeper:

  /** Create and start a timeout sweeper that reconstructs per claimed instance id.
    *
    * The sweeper starts immediately and runs until the scope closes or `stop` is called. Either one releases a held
    * leader lease. After claiming a row it runs `open(timeout.instanceId)` inside `ZIO.scoped`, then `send`s on that
    * runtime.
    *
    * `open` should be `id => FSMRuntime.existing(id, machine, initial)` for load-on-demand servers. Use durable
    * timeouts on that reconstruct; fiber timeouts leak across request/sweeper scopes.
    *
    * @param config
    *   Sweeper configuration (intervals, batch size, jitter, etc.)
    * @param timeoutStore
    *   Storage for scheduled timeouts
    * @param open
    *   Open a runtime for the claimed instance (dropped when the inner scope closes)
    * @param leaseStore
    *   Optional lease store for leader election (required if config.leaderElection is set)
    */
  def make[Id: Tag, S, E](
      config: TimeoutSweeperConfig,
      timeoutStore: TimeoutStore[Id],
      open: Id => ZIO[Scope, MechanoidError, FSMRuntime[Id, S, E]],
      leaseStore: Option[LeaseStore] = None,
  ): ZIO[Scope & InstanceMailbox[Id], MechanoidError, TimeoutSweeper] =
    ZIO.serviceWithZIO[InstanceMailbox[Id]] { box =>
      start(TimeoutSweeperImpl(config, timeoutStore, open, box, leaseStore))
    }

  /** Heartbeat helper: one long-lived runtime. Only that instance id is claimed.
    */
  def pinned[Id: Tag, S, E](
      config: TimeoutSweeperConfig,
      timeoutStore: TimeoutStore[Id],
      runtime: FSMRuntime[Id, S, E],
      leaseStore: Option[LeaseStore] = None,
  ): ZIO[Scope & InstanceMailbox[Id], MechanoidError, TimeoutSweeper] =
    val pinnedOpen: Id => ZIO[Scope, MechanoidError, FSMRuntime[Id, S, E]] =
      id =>
        if id == runtime.instanceId then ZIO.succeed(runtime)
        else ZIO.fail(InstanceMismatchError(runtime.instanceId.toString, id.toString))
    ZIO.serviceWithZIO[InstanceMailbox[Id]] { box =>
      start(
        TimeoutSweeperImpl(
          config,
          timeoutStore,
          pinnedOpen,
          box,
          leaseStore,
          _ == runtime.instanceId,
        )
      )
    }
  end pinned

  private def start[Id, S, E](
      impl: TimeoutSweeperImpl[Id, S, E]
  ): ZIO[Scope, MechanoidError, TimeoutSweeper] =
    for
      runningRef <- Ref.make(true)
      metricsRef <- Ref.make(SweeperMetrics.empty)

      leaderElection <- impl.config.leaderElection match
        case Some(leConfig) =>
          impl.leaseStore match
            case Some(ls) =>
              LeaderElection.make(leConfig, impl.config.nodeId, ls).map(Some(_))
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

      loop <- runSweepLoop(
        impl,
        runningRef,
        metricsRef,
        leaderElection,
      ).forkScoped
      sweeper = new TimeoutSweeper:
        def isRunning: UIO[Boolean]      = runningRef.get
        def metrics: UIO[SweeperMetrics] = metricsRef.get
        def stop: UIO[Unit]              =
          runningRef.set(false) *>
            loop.interrupt *>
            leaderElection.fold(ZIO.unit)(_.resign)
      // Scope close is shutdown. Resign so the next node does not wait out the lease.
      _ <- ZIO.addFinalizer(sweeper.stop)
    yield sweeper
    end for
  end start

  private def runSweepLoop[Id, S, E](
      impl: TimeoutSweeperImpl[Id, S, E],
      runningRef: Ref[Boolean],
      metricsRef: Ref[SweeperMetrics],
      leaderElection: Option[LeaderElection],
  ): ZIO[Any, Nothing, Unit] =

    val sweep: ZIO[Any, Nothing, Boolean] =
      for
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
        _ <- impl.config.backoffOnEmpty match
          case Some(backoff) if count == 0 => ZIO.sleep(backoff)
          case _                           => ZIO.unit
        shouldContinue <- runningRef.get
      yield shouldContinue

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
    if !impl.accept(timeout.instanceId) then ZIO.succeed(false)
    else
      impl.mailbox.run(timeout.instanceId) {
        Ref.make(false).flatMap { settled =>
          val body =
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
                case ClaimResult.Claimed(claimed) =>
                  val row = timeout.copy(
                    stateHash = claimed.stateHash,
                    sequenceNr = claimed.sequenceNr,
                    deadline = claimed.deadline,
                    claimedBy = claimed.claimedBy,
                    claimedUntil = claimed.claimedUntil,
                  )
                  if row.isExpired(now) then fireClaimed(impl, row, metricsRef, settled)
                  else
                    releaseOwned(impl, timeout).tap(_ => settled.set(true)) *>
                      skip(metricsRef)

                case ClaimResult.AlreadyClaimed(_, _) =>
                  settled.set(true) *>
                    metricsRef.update(m => m.copy(claimConflicts = m.claimConflicts + 1)).as(false)

                case ClaimResult.NotFound | ClaimResult.StateChanged(_) | ClaimResult.NotDue =>
                  settled.set(true) *> skip(metricsRef)
            yield fired
          body.ensuring {
            settled.get.flatMap {
              case true  => ZIO.unit
              case false => releaseOwned(impl, timeout).ignore
            }
          }
        }
      }

  private def fireClaimed[Id, S, E](
      impl: TimeoutSweeperImpl[Id, S, E],
      row: ScheduledTimeout[Id],
      metricsRef: Ref[SweeperMetrics],
      settled: Ref[Boolean],
  ): ZIO[Any, MechanoidError, Boolean] =
    val id                                                = row.instanceId
    val name                                              = row.name
    def finish(effect: ZIO[Any, MechanoidError, Boolean]) =
      effect.tap(_ => settled.set(true))

    ZIO
      .scoped {
        impl.open(id).flatMap { runtime =>
          for
            currentState <- runtime.currentState
            currentStateHash = runtime.machine.stateEnum.caseHash(currentState)
            eventOpt         = impl.resolveTimeoutEvent(runtime, currentState, name)
            result <-
              if currentStateHash == row.stateHash then
                eventOpt match
                  case Some(event) =>
                    runtime
                      .send(event)
                      .foldZIO(
                        error => finish(releaseForRetry(impl, row, metricsRef, error)),
                        _ =>
                          finish(
                            impl.timeoutStore.complete(id, name, row.sequenceNr) *>
                              metricsRef.update(m => m.copy(timeoutsFired = m.timeoutsFired + 1)).as(true)
                          ),
                      )
                  case None =>
                    finish(
                      ZIO.logWarning(s"No timeout named $name on current leaf for $id") *>
                        impl.timeoutStore.complete(id, name, row.sequenceNr) *>
                        skip(metricsRef)
                    )
              else
                finish(
                  ZIO.logDebug(
                    s"Skipping stale timeout $name for $id: " +
                      s"expected stateHash=${row.stateHash}, actual=$currentStateHash"
                  ) *>
                    impl.timeoutStore.complete(id, name, row.sequenceNr) *>
                    skip(metricsRef)
                )
          yield result
        }
      }
      .catchAll {
        case _: InstanceNotFoundError =>
          finish(
            ZIO.logWarning(s"Completing orphan timeout $name for missing instance $id") *>
              impl.timeoutStore.complete(id, name, row.sequenceNr) *>
              skip(metricsRef)
          )
        case error =>
          finish(releaseForRetry(impl, row, metricsRef, error))
      }
  end fireClaimed

  private def skip(metricsRef: Ref[SweeperMetrics]): ZIO[Any, Nothing, Boolean] =
    metricsRef.update(m => m.copy(timeoutsSkipped = m.timeoutsSkipped + 1)).as(false)

  private def releaseOwned[Id, S, E](
      impl: TimeoutSweeperImpl[Id, S, E],
      timeout: ScheduledTimeout[Id],
  ): ZIO[Any, MechanoidError, Boolean] =
    impl.timeoutStore.release(timeout.instanceId, timeout.name, impl.config.nodeId)

  private def releaseForRetry[Id, S, E](
      impl: TimeoutSweeperImpl[Id, S, E],
      timeout: ScheduledTimeout[Id],
      metricsRef: Ref[SweeperMetrics],
      error: MechanoidError,
  ): ZIO[Any, MechanoidError, Boolean] =
    releaseOwned(impl, timeout) *>
      metricsRef.update(m => m.copy(errors = m.errors + 1)) *>
      ZIO.logWarning(s"Failed to fire timeout ${timeout.name} for ${timeout.instanceId}: $error").as(false)
end TimeoutSweeper
