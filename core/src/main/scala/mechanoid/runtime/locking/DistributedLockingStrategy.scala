package mechanoid.runtime.locking

import zio.*
import mechanoid.core.{LockingError, MechanoidError}
import mechanoid.persistence.lock.{FSMInstanceLock, LockConfig, LockError, LockHeartbeatConfig}

/** Distributed locking strategy that acquires exclusive locks via FSMInstanceLock.
  *
  * This strategy acquires a distributed lock before executing any effect, ensuring only one node can process a given
  * FSM instance at a time. This prevents concurrent modification conflicts entirely (rather than detecting them
  * after-the-fact like optimistic locking).
  *
  * ==When to Use==
  *
  *   - Production deployments with multiple nodes
  *   - High-contention scenarios where conflicts are common
  *   - When you want to avoid wasted work from failed optimistic writes
  *
  * ==Trade-offs==
  *
  *   - '''Pro''': No wasted work - only one node processes at a time
  *   - '''Pro''': Predictable behavior under contention
  *   - '''Con''': Lock acquisition latency on every operation
  *   - '''Con''': Requires a distributed lock service (database, Redis, etc.)
  *
  * ==Lock Duration and Timeouts==
  *
  * Configure via [[LockConfig]]:
  *   - `lockDuration`: How long to hold locks (default 30s)
  *   - `acquireTimeout`: Max wait time when lock is busy (default 10s)
  *   - `validateBeforeOperation`: Check lock validity before each op (default true)
  *
  * @tparam Id
  *   FSM instance identifier type
  */
final class DistributedLockingStrategy[Id] private (
    lock: FSMInstanceLock[Id],
    config: LockConfig,
) extends LockingStrategy[Id]:

  override def withLock[R, E >: MechanoidError, A](
      instanceId: Id,
      effect: ZIO[R, E, A],
  ): ZIO[R, E, A] =
    lock
      .withLock(instanceId, config.nodeId, config.lockDuration, Some(config.acquireTimeout)) {
        // Optionally validate lock is still held before proceeding
        validateLockIfConfigured(instanceId) *> effect
      }
      .mapError {
        case e: LockError      => LockingError(e)
        case e: MechanoidError => e
        case e                 => e // Pass through other errors
      }

  /** Validate that we still hold the lock (if configured). */
  private def validateLockIfConfigured(instanceId: Id): ZIO[Any, LockError, Unit] =
    if config.validateBeforeOperation then
      Clock.instant.flatMap { now =>
        lock
          .get(instanceId, now)
          .flatMap {
            case Some(token) if token.nodeId == config.nodeId =>
              ZIO.unit
            case Some(token) =>
              ZIO.fail(LockError.LockBusy(instanceId.toString, token.nodeId, token.expiresAt))
            case None =>
              // Lock was released/expired - this shouldn't happen inside withLock
              // but check anyway for safety
              ZIO.fail(
                LockError.LockTimeout(instanceId.toString, config.lockDuration)
              )
          }
          .mapError {
            case e: LockError      => e
            case e: MechanoidError =>
              LockError.LockAcquisitionFailed(instanceId.toString, new RuntimeException(e.toString))
          }
      }
    else ZIO.unit

  /** Execute multiple operations atomically while holding a single lock with heartbeat renewal.
    *
    * Use this for orchestration logic that requires multiple operations without interleaving from other nodes. The
    * heartbeat keeps the lock alive during the orchestration.
    *
    * ==When to Use==
    *
    *   - Conditional operations based on intermediate state
    *   - Saga-like patterns where multiple events form one logical operation
    *   - Reading state between operations for branching logic
    *
    * ==Lock Loss Behavior==
    *
    * By default, if the lock is lost (renewal fails), the effect is interrupted immediately (FailFast). This is safe
    * because another node may have acquired the lock.
    *
    * @param instanceId
    *   The FSM instance to lock
    * @param heartbeat
    *   Configuration for lock renewal (defaults to sensible values)
    * @param effect
    *   The effect to execute while holding the lock
    * @return
    *   The result of the effect, or error if lock couldn't be acquired/maintained
    */
  def withAtomicOperations[R, E >: MechanoidError, A](
      instanceId: Id,
      heartbeat: LockHeartbeatConfig = LockHeartbeatConfig(),
  )(effect: ZIO[R, E, A]): ZIO[R, E | LockError, A] =
    lock.withLockAndHeartbeat(instanceId, config.nodeId, config.lockDuration, heartbeat = heartbeat)(effect)

end DistributedLockingStrategy

object DistributedLockingStrategy:

  /** Create a distributed locking strategy with default configuration.
    *
    * @param lock
    *   The distributed lock service
    * @return
    *   A new strategy instance wrapped in ZIO (for nodeId generation)
    */
  def make[Id](lock: FSMInstanceLock[Id]): UIO[DistributedLockingStrategy[Id]] =
    LockConfig.default.map(config => new DistributedLockingStrategy(lock, config))

  /** Create a distributed locking strategy with custom configuration.
    *
    * @param lock
    *   The distributed lock service
    * @param config
    *   Lock configuration
    * @return
    *   A new strategy instance
    */
  def make[Id](lock: FSMInstanceLock[Id], config: LockConfig): DistributedLockingStrategy[Id] =
    new DistributedLockingStrategy(lock, config)

  /** Layer providing a distributed locking strategy with default configuration.
    *
    * Requires `FSMInstanceLock[Id]` in the environment.
    */
  def layer[Id: Tag]: URLayer[FSMInstanceLock[Id], LockingStrategy[Id]] =
    ZLayer.fromZIO(ZIO.serviceWithZIO[FSMInstanceLock[Id]](lock => make(lock)))

  /** Layer providing a distributed locking strategy with explicit configuration.
    *
    * Requires `FSMInstanceLock[Id]` in the environment.
    */
  def layer[Id: Tag](config: LockConfig): URLayer[FSMInstanceLock[Id], LockingStrategy[Id]] =
    ZLayer.fromFunction((lock: FSMInstanceLock[Id]) => make(lock, config))

  /** Layer providing a distributed locking strategy with configuration from environment.
    *
    * Requires both `FSMInstanceLock[Id]` and `LockConfig` in the environment.
    */
  def layerWithConfig[Id: Tag]: URLayer[FSMInstanceLock[Id] & LockConfig, LockingStrategy[Id]] =
    ZLayer.fromFunction((lock: FSMInstanceLock[Id], config: LockConfig) => make(lock, config))

end DistributedLockingStrategy
