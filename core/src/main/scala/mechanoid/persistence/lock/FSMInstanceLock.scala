package mechanoid.persistence.lock

import zio.*
import java.time.Instant
import mechanoid.core.MechanoidError

/** Distributed lock for FSM instances to ensure exactly-once transition semantics.
  *
  * When multiple nodes might process events for the same FSM instance concurrently, use this lock to ensure mutual
  * exclusion. Only the node holding the lock can process events; others wait or fail fast.
  *
  * ==Why Use Instance Locking?==
  *
  * Without locking, concurrent event processing relies on optimistic locking (sequence numbers) which detects conflicts
  * ''after'' they happen. This leads to:
  *   - Wasted work (processing that gets rejected)
  *   - Retry overhead
  *   - Potential for subtle race conditions
  *
  * With instance locking, conflicts are ''prevented'' rather than detected.
  *
  * ==Lock Semantics==
  *
  *   - '''Mutual exclusion''': Only one node can hold the lock at a time
  *   - '''Lease-based''': Locks expire automatically (crash recovery)
  *   - '''Reentrant-safe''': Same node can extend its own lock
  *   - '''Fair ordering''': Not guaranteed (depends on implementation)
  *
  * ==Usage==
  *
  * The lock is typically used automatically by `FSMRuntime.withLocking`:
  *
  * {{{
  * val program = ZIO.scoped {
  *   for
  *     fsm <- FSMRuntime.withLocking(orderId, definition, Pending)
  *     _   <- fsm.send(Pay)  // Lock acquired automatically
  *   yield ()
  * }.provide(eventStoreLayer, lockLayer)
  * }}}
  *
  * Or use directly for fine-grained control:
  *
  * {{{
  * lock.withLock(orderId, nodeId, 30.seconds) {
  *   // Exclusive access to this FSM instance
  *   fsm.send(Pay)
  * }
  * }}}
  *
  * ==PostgreSQL Implementation==
  *
  * {{{
  * CREATE TABLE fsm_instance_locks (
  *   instance_id  TEXT PRIMARY KEY,
  *   node_id      TEXT NOT NULL,
  *   acquired_at  TIMESTAMPTZ NOT NULL,
  *   expires_at   TIMESTAMPTZ NOT NULL
  * );
  *
  * -- Acquire lock (atomic upsert if expired or same node)
  * INSERT INTO fsm_instance_locks (instance_id, node_id, acquired_at, expires_at)
  * VALUES ($1, $2, NOW(), NOW() + $3::interval)
  * ON CONFLICT (instance_id) DO UPDATE
  *   SET node_id = EXCLUDED.node_id,
  *       acquired_at = EXCLUDED.acquired_at,
  *       expires_at = EXCLUDED.expires_at
  *   WHERE fsm_instance_locks.expires_at < NOW()
  *      OR fsm_instance_locks.node_id = EXCLUDED.node_id
  * RETURNING *;
  * }}}
  *
  * @tparam Id
  *   The FSM instance identifier type
  */
trait FSMInstanceLock[Id]:

  /** Try to acquire the lock without waiting.
    *
    * @param instanceId
    *   The FSM instance to lock
    * @param nodeId
    *   The node requesting the lock
    * @param duration
    *   How long the lock should be held
    * @param now
    *   Current timestamp
    * @return
    *   Acquired with token, or Busy if held by another node
    */
  def tryAcquire(
      instanceId: Id,
      nodeId: String,
      duration: Duration,
      now: Instant,
  ): ZIO[Any, MechanoidError, LockResult[Id]]

  /** Acquire the lock, waiting if necessary.
    *
    * Polls for the lock until acquired or timeout. Uses exponential backoff to reduce contention.
    *
    * @param instanceId
    *   The FSM instance to lock
    * @param nodeId
    *   The node requesting the lock
    * @param duration
    *   How long the lock should be held once acquired
    * @param timeout
    *   Maximum time to wait for the lock
    * @return
    *   Acquired with token, or TimedOut if couldn't acquire in time
    */
  def acquire(
      instanceId: Id,
      nodeId: String,
      duration: Duration,
      timeout: Duration,
  ): ZIO[Any, MechanoidError, LockResult[Id]]

  /** Release a held lock.
    *
    * Should be called when done processing to allow other nodes to proceed. The lock will also expire automatically
    * after its duration.
    *
    * @param token
    *   The lock token from a successful acquire
    * @return
    *   true if released, false if lock was already released/expired
    */
  def release(token: LockToken[Id]): ZIO[Any, MechanoidError, Boolean]

  /** Extend the duration of a held lock.
    *
    * Use this for long-running operations to prevent expiration.
    *
    * @param token
    *   The current lock token
    * @param additionalDuration
    *   How much longer to hold the lock
    * @param now
    *   Current timestamp
    * @return
    *   New token with extended expiration, or None if lock was lost
    */
  def extend(
      token: LockToken[Id],
      additionalDuration: Duration,
      now: Instant,
  ): ZIO[Any, MechanoidError, Option[LockToken[Id]]]

  /** Get the current lock holder for an instance.
    *
    * @param instanceId
    *   The FSM instance to check
    * @param now
    *   Current timestamp (to filter expired locks)
    * @return
    *   The lock token if locked, None if available
    */
  def get(instanceId: Id, now: Instant): ZIO[Any, MechanoidError, Option[LockToken[Id]]]

  /** Execute an effect while holding the lock.
    *
    * This is the recommended way to use locking. The lock is automatically acquired before the effect and released
    * after (even on failure).
    *
    * {{{
    * lock.withLock(orderId, nodeId, 30.seconds) {
    *   fsm.send(Pay)
    * }
    * }}}
    *
    * @param instanceId
    *   The FSM instance to lock
    * @param nodeId
    *   The node requesting the lock
    * @param duration
    *   How long the lock should be held
    * @param timeout
    *   Maximum time to wait for the lock (default: same as duration)
    * @param effect
    *   The effect to execute while holding the lock
    * @return
    *   The effect result, or fails with LockError if couldn't acquire
    */
  def withLock[R, E, A](
      instanceId: Id,
      nodeId: String,
      duration: Duration,
      timeout: Option[Duration] = None,
  )(effect: ZIO[R, E, A]): ZIO[R, E | LockError, A] =
    ZIO.acquireReleaseWith(
      acquire(instanceId, nodeId, duration, timeout.getOrElse(duration))
        .flatMap {
          case LockResult.Acquired(token)     => ZIO.succeed(token)
          case LockResult.Busy(heldBy, until) =>
            ZIO.fail(LockError.LockBusy(instanceId.toString, heldBy, until))
          case LockResult.TimedOut() =>
            ZIO.fail(LockError.LockTimeout(instanceId.toString, timeout.getOrElse(duration)))
        }
        .mapError {
          case e: LockError => e
          case e            => LockError.LockAcquisitionFailed(instanceId.toString, new RuntimeException(e.toString))
        }
    )(token => release(token).ignore)(_ => effect)

  /** Execute an effect while holding the lock with automatic heartbeat renewal.
    *
    * Similar to [[withLock]], but also starts a background fiber that periodically renews the lock. This prevents lock
    * expiration during long-running operations.
    *
    * ==FailFast vs Continue==
    *
    * When the heartbeat fails to renew the lock:
    *
    *   - '''FailFast''' (default): The main effect is interrupted immediately. This is safe for non-idempotent
    *     operations since another node may have acquired the lock.
    *   - '''Continue''': The `onLockLost` effect runs, then execution continues. Only use this for idempotent
    *     operations where completing is more important than safety.
    *
    * ==Usage==
    *
    * {{{
    * // Default: FailFast if lock is lost
    * lock.withLockAndHeartbeat(orderId, nodeId, 30.seconds) {
    *   longRunningOperation()
    * }
    *
    * // Custom config with Continue behavior
    * val config = LockHeartbeatConfig(
    *   renewalInterval = 5.seconds,
    *   onLockLost = LockLostBehavior.Continue(
    *     ZIO.logWarning("Lock lost, but continuing...")
    *   )
    * )
    * lock.withLockAndHeartbeat(orderId, nodeId, 30.seconds, heartbeat = config) {
    *   idempotentOperation()
    * }
    * }}}
    *
    * ==Important==
    *
    * This is for operations that need extended lock duration. For fast operations, prefer [[withLock]]. For FSM
    * operations that generate commands, the actual work should be delegated to the Command pattern - don't hold locks
    * while calling external services.
    *
    * @param instanceId
    *   The FSM instance to lock
    * @param nodeId
    *   The node requesting the lock
    * @param duration
    *   Initial lock duration
    * @param timeout
    *   Maximum time to wait for the lock (default: same as duration)
    * @param heartbeat
    *   Heartbeat configuration (renewal interval, duration, and lock-lost behavior)
    * @param effect
    *   The effect to execute while holding the lock
    * @return
    *   The effect result, or fails with LockError if couldn't acquire or was interrupted due to lock loss
    */
  def withLockAndHeartbeat[R, E, A](
      instanceId: Id,
      nodeId: String,
      duration: Duration,
      timeout: Option[Duration] = None,
      heartbeat: LockHeartbeatConfig = LockHeartbeatConfig(),
  )(effect: ZIO[R, E, A]): ZIO[R, E | LockError, A] =
    val self = this
    ZIO.scoped {
      for
        // Acquire the lock
        token <- acquire(instanceId, nodeId, duration, timeout.getOrElse(duration))
          .flatMap {
            case LockResult.Acquired(token)     => ZIO.succeed(token)
            case LockResult.Busy(heldBy, until) =>
              ZIO.fail(LockError.LockBusy(instanceId.toString, heldBy, until))
            case LockResult.TimedOut() =>
              ZIO.fail(LockError.LockTimeout(instanceId.toString, timeout.getOrElse(duration)))
          }
          .mapError {
            case e: LockError => e
            case e            => LockError.LockAcquisitionFailed(instanceId.toString, new RuntimeException(e.toString))
          }

        // Track current token and main fiber
        tokenRef      <- Ref.make(token)
        mainFiber     <- Ref.make[Option[Fiber.Runtime[?, ?]]](None)
        lockLost      <- Ref.make(false)
        stopHeartbeat <- Promise.make[Nothing, Unit]

        // Register finalizer to release lock on exit (success, failure, or interruption)
        _ <- ZIO.addFinalizer(tokenRef.get.flatMap(t => self.release(t).ignore))

        // Heartbeat renewal effect
        renewLock =
          for
            currentToken <- tokenRef.get
            now          <- Clock.instant
            result       <- self
              .extend(currentToken, heartbeat.renewalDuration, now)
              .catchAll { error =>
                ZIO.logWarning(s"Lock renewal failed: $error").as(None)
              }
            _ <- result match
              case Some(newToken) =>
                tokenRef.set(newToken)
              case None =>
                // Lock lost - handle according to config
                // IMPORTANT: Run the handler BEFORE stopping heartbeat, because
                // stopHeartbeat.succeed() causes the race to complete and could
                // interrupt this fiber before the handler runs.
                lockLost.set(true) *>
                  (heartbeat.onLockLost match
                    case LockLostBehavior.FailFast =>
                      mainFiber.get.flatMap {
                        case Some(fiber) => fiber.interrupt.unit
                        case None        => ZIO.unit
                      }
                    case LockLostBehavior.Continue(onLockLost) =>
                      onLockLost) *>
                  stopHeartbeat.succeed(()).unit
          yield ()

        // Start heartbeat fiber (scoped - will be interrupted when scope closes)
        // jittered(min, max) scales delay by random factor in [min, max]
        schedule = Schedule
          .spaced(heartbeat.renewalInterval)
          .jittered(1.0 - heartbeat.jitterFactor, 1.0 + heartbeat.jitterFactor)
        _ <- renewLock
          .repeat(schedule)
          .race(stopHeartbeat.await)
          .forkScoped

        // Run main effect, tracking fiber for potential interrupt
        // Lock will be released by finalizer on exit (success, failure, or interruption)
        result <- effect.fork.flatMap { fiber =>
          mainFiber.set(Some(fiber)) *> fiber.join
        }
      yield result
    }
  end withLockAndHeartbeat
end FSMInstanceLock

object FSMInstanceLock:

  /** Default lock duration for automatic locking. */
  val DefaultLockDuration: Duration = Duration.fromSeconds(30)

  /** Default timeout for acquiring locks. */
  val DefaultLockTimeout: Duration = Duration.fromSeconds(10)

/** Behavior when lock renewal fails during heartbeat.
  *
  * Controls how [[FSMInstanceLock.withLockAndHeartbeat]] responds when the lock cannot be renewed.
  */
sealed trait LockLostBehavior

object LockLostBehavior:

  /** Interrupt the main effect immediately when lock is lost.
    *
    * This is the safe default for non-idempotent operations. If the lock is lost, another node may have acquired it and
    * started processing. Continuing could cause duplicate processing or race conditions.
    */
  case object FailFast extends LockLostBehavior

  /** Run the provided effect when lock is lost, then continue execution.
    *
    * Use this only for idempotent operations where completing the work is preferred over safety. The `onLockLost`
    * effect runs when renewal fails, allowing you to log, emit metrics, or take other actions.
    *
    * '''Warning''': Using this with non-idempotent operations risks duplicate processing.
    *
    * @param onLockLost
    *   Effect to run when the lock is lost (defaults to no-op)
    */
  case class Continue(onLockLost: ZIO[Any, Nothing, Unit] = ZIO.unit) extends LockLostBehavior
end LockLostBehavior

/** Configuration for automatic lock heartbeat renewal.
  *
  * When using [[FSMInstanceLock.withLockAndHeartbeat]], the lock is automatically renewed in the background to prevent
  * expiration during long-running operations.
  *
  * ==Recommended Settings==
  *
  * Set `renewalInterval` to be significantly less than `renewalDuration` to ensure the lock doesn't expire between
  * renewal attempts. A good rule of thumb:
  *
  * {{{
  * renewalInterval <= renewalDuration / 3
  * }}}
  *
  * This allows for 2 missed renewals before the lock expires.
  *
  * ==Example==
  *
  * {{{
  * val config = LockHeartbeatConfig(
  *   renewalInterval = 10.seconds,
  *   renewalDuration = 30.seconds,
  *   jitterFactor = 0.1,
  *   onLockLost = LockLostBehavior.FailFast
  * )
  *
  * lock.withLockAndHeartbeat(orderId, nodeId, 30.seconds, heartbeat = config) {
  *   // Long-running operation - lock is automatically renewed
  *   processOrder(orderId)
  * }
  * }}}
  *
  * @param renewalInterval
  *   How often to renew the lock (default: 10 seconds)
  * @param renewalDuration
  *   How long to extend the lock on each renewal (default: 30 seconds)
  * @param jitterFactor
  *   Random jitter to add to renewal interval (0.0 to 1.0, default: 0.1)
  * @param onLockLost
  *   Behavior when lock renewal fails (default: FailFast)
  */
final case class LockHeartbeatConfig(
    renewalInterval: Duration = Duration.fromSeconds(10),
    renewalDuration: Duration = Duration.fromSeconds(30),
    jitterFactor: Double = 0.1,
    onLockLost: LockLostBehavior = LockLostBehavior.FailFast,
)
