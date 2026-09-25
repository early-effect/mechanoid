package mechanoid.runtime.locking

import zio.*
import mechanoid.core.MechanoidError
import mechanoid.persistence.lock.FSMInstanceLock

/** Strategy for managing FSM instance locking.
  *
  * This trait defines how concurrent access to FSM instances is handled. Different implementations provide different
  * consistency and performance trade-offs:
  *
  *   - [[OptimisticLockingStrategy]]: No explicit locking. Relies on EventStore's sequence number conflict detection.
  *     Fast, but may waste work on conflicts.
  *   - [[DistributedLockingStrategy]]: Acquires an exclusive lock before processing. Prevents conflicts but adds
  *     latency.
  *
  * ==Usage==
  *
  * The locking strategy is an environment dependency for FSMRuntime. Use the companion object layer helpers:
  *
  * {{{
  * // Optimistic locking (development, low contention)
  * val program = ZIO.scoped {
  *   for
  *     fsm <- FSMRuntime(id, machine, initial)
  *     _   <- fsm.send(event)
  *   yield ()
  * }.provide(
  *   eventStoreLayer,
  *   TimeoutStrategy.fiber[OrderId],
  *   LockingStrategy.optimistic[OrderId]
  * )
  *
  * // Distributed locking (production, high contention)
  * val program = ZIO.scoped {
  *   for
  *     fsm <- FSMRuntime(id, machine, initial)
  *     _   <- fsm.send(event)
  *   yield ()
  * }.provide(
  *   eventStoreLayer,
  *   lockServiceLayer,
  *   TimeoutStrategy.fiber[OrderId],
  *   LockingStrategy.distributed[OrderId]
  * )
  * }}}
  *
  * ==Choosing a Strategy==
  *
  * | Strategy      | When to Use                                                       |
  * |:--------------|:------------------------------------------------------------------|
  * | `optimistic`  | Development, testing, low-contention production, single-node      |
  * | `distributed` | High-contention production, multi-node where conflicts are costly |
  *
  * ==Trade-offs==
  *
  *   - '''Optimistic''': No latency overhead, but wasted work when conflicts occur
  *   - '''Distributed''': Lock acquisition latency, but no wasted work
  *
  * @tparam Id
  *   FSM instance identifier type
  */
trait LockingStrategy[Id]:

  /** Execute an effect while holding appropriate lock semantics.
    *
    * For optimistic: just run the effect (rely on EventStore conflict detection) For distributed: acquire lock, run
    * effect, release lock
    *
    * @param instanceId
    *   The FSM instance identifier
    * @param effect
    *   The effect to execute
    * @return
    *   The result of the effect, or a lock error if locking failed
    */
  def withLock[R, E >: MechanoidError, A](
      instanceId: Id,
      effect: ZIO[R, E, A],
  ): ZIO[R, E, A]

  /** Drop this instance's lock row, if the strategy has one.
    *
    * Optimistic locking has no row. Distributed locking deletes the row for any holder, including an expired lease.
    * `fsm.delete` calls this after the log is gone.
    */
  def releaseInstance(instanceId: Id): ZIO[Any, MechanoidError, Unit]

end LockingStrategy

object LockingStrategy:

  /** Access the locking strategy from the environment. */
  def withLock[Id: Tag, R, E >: MechanoidError, A](
      instanceId: Id,
      effect: ZIO[R, E, A],
  ): ZIO[R & LockingStrategy[Id], E, A] =
    ZIO.serviceWithZIO[LockingStrategy[Id]](_.withLock(instanceId, effect))

  // ============================================
  // Layer constructors
  // ============================================

  /** Layer providing optimistic locking (no explicit locking).
    *
    * Relies on EventStore's sequence number conflict detection. Fast but may waste work on conflicts. Good for
    * development, testing, and low-contention scenarios.
    */
  def optimistic[Id: Tag]: ULayer[LockingStrategy[Id]] =
    OptimisticLockingStrategy.layer[Id]

  /** Layer providing distributed locking using FSMInstanceLock.
    *
    * Acquires exclusive locks before processing. Prevents conflicts but adds latency. Good for production and
    * high-contention scenarios. Requires `FSMInstanceLock[Id]` in the environment.
    */
  def distributed[Id: Tag]: URLayer[FSMInstanceLock[Id], LockingStrategy[Id]] =
    DistributedLockingStrategy.layer[Id]

  /** Layer providing distributed locking with explicit configuration.
    *
    * Same as [[distributed]] but allows specifying lock configuration explicitly rather than using defaults.
    */
  def distributed[Id: Tag](
      config: mechanoid.persistence.lock.LockConfig
  ): URLayer[FSMInstanceLock[Id], LockingStrategy[Id]] =
    DistributedLockingStrategy.layer[Id](config)

end LockingStrategy
