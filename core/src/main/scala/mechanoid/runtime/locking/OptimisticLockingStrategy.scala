package mechanoid.runtime.locking

import zio.*
import mechanoid.core.MechanoidError
import scala.annotation.unused

/** Optimistic locking strategy that relies on EventStore conflict detection.
  *
  * This strategy does not acquire any explicit locks. Instead, it relies on the EventStore's sequence number validation
  * to detect concurrent modifications. If two nodes try to process the same FSM instance simultaneously:
  *
  *   1. Both execute their transitions
  *   2. Both try to append events to the EventStore
  *   3. Only one succeeds (the one with the correct expected sequence number)
  *   4. The other gets a `SequenceConflictError` and must retry
  *
  * ==When to Use==
  *
  *   - Development and testing
  *   - Low-contention scenarios where conflicts are rare
  *   - When you want maximum throughput and can handle occasional retries
  *
  * ==Trade-offs==
  *
  *   - '''Pro''': No lock acquisition latency
  *   - '''Pro''': No lock service dependency
  *   - '''Con''': Wasted work when conflicts occur (transition executed but not persisted)
  *   - '''Con''': Higher retry rates under contention
  *
  * @tparam Id
  *   FSM instance identifier type
  */
final class OptimisticLockingStrategy[Id] private () extends LockingStrategy[Id]:

  override def withLock[R, E >: MechanoidError, A](
      instanceId: Id,
      effect: ZIO[R, E, A],
  ): ZIO[R, E, A] =
    // No explicit locking - just execute the effect directly
    // EventStore's sequence number validation provides conflict detection
    effect

  override def releaseInstance(@unused instanceId: Id): ZIO[Any, MechanoidError, Unit] =
    ZIO.unit
end OptimisticLockingStrategy

object OptimisticLockingStrategy:

  /** Create a new optimistic locking strategy. */
  def make[Id]: OptimisticLockingStrategy[Id] =
    new OptimisticLockingStrategy[Id]

  /** Layer providing an optimistic locking strategy. */
  def layer[Id: Tag]: ULayer[LockingStrategy[Id]] =
    ZLayer.succeed(make[Id])

end OptimisticLockingStrategy
