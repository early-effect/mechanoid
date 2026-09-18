package mechanoid.runtime.timeout

import zio.*

/** Strategy for managing FSM state timeouts.
  *
  * This trait defines how timeouts are scheduled and cancelled for FSM instances. Different implementations provide
  * different durability and failure semantics:
  *
  *   - [[FiberTimeoutStrategy]]: In-memory, uses ZIO fibers. Fast but doesn't survive node failures.
  *   - [[DurableTimeoutStrategy]]: Persists to a [[mechanoid.persistence.timeout.TimeoutStore]]. Survives node failures
  *     when combined with a [[mechanoid.persistence.timeout.TimeoutSweeper]].
  *
  * @tparam Id
  *   FSM instance identifier type
  */
trait TimeoutStrategy[Id]:

  /** Schedule a named timeout for the given FSM instance.
    *
    * When the timeout fires, it should invoke the provided callback. Scheduling the same name replaces that timeout;
    * other names on the instance stay armed.
    *
    * For durable timeouts, `stateHash` is persisted so the sweeper can skip rows after Goto away. Reconstructing a
    * runtime that is still in the same leaf reuses the existing absolute deadline (see [[DurableTimeoutStrategy]]).
    */
  def schedule(
      instanceId: Id,
      name: String,
      stateHash: Int,
      sequenceNr: Long,
      deadline: java.time.Instant,
      onTimeout: UIO[Unit],
  ): UIO[Unit]

  /** Cancel every pending timeout for the given FSM instance. */
  def cancel(instanceId: Id): UIO[Unit]

  /** Cancel one named timeout. Idempotent if that name is not armed. */
  def cancel(instanceId: Id, name: String): UIO[Unit]

  /** Drop names not in `keep` for this instance. Used on reconstruct / enter so extra keys do not linger. */
  def retain(instanceId: Id, keep: Set[String]): UIO[Unit]

end TimeoutStrategy

object TimeoutStrategy:

  def schedule[Id: Tag](
      instanceId: Id,
      name: String,
      stateHash: Int,
      sequenceNr: Long,
      deadline: java.time.Instant,
      onTimeout: UIO[Unit],
  ): ZIO[TimeoutStrategy[Id], Nothing, Unit] =
    ZIO.serviceWithZIO[TimeoutStrategy[Id]](_.schedule(instanceId, name, stateHash, sequenceNr, deadline, onTimeout))

  def cancel[Id: Tag](instanceId: Id): ZIO[TimeoutStrategy[Id], Nothing, Unit] =
    ZIO.serviceWithZIO[TimeoutStrategy[Id]](_.cancel(instanceId))

  def cancel[Id: Tag](instanceId: Id, name: String): ZIO[TimeoutStrategy[Id], Nothing, Unit] =
    ZIO.serviceWithZIO[TimeoutStrategy[Id]](_.cancel(instanceId, name))

  def retain[Id: Tag](instanceId: Id, keep: Set[String]): ZIO[TimeoutStrategy[Id], Nothing, Unit] =
    ZIO.serviceWithZIO[TimeoutStrategy[Id]](_.retain(instanceId, keep))

  def fiber[Id: Tag]: ULayer[TimeoutStrategy[Id]] =
    FiberTimeoutStrategy.layer[Id]

  def durable[Id: Tag]: URLayer[mechanoid.persistence.timeout.TimeoutStore[Id], TimeoutStrategy[Id]] =
    DurableTimeoutStrategy.layer[Id]

end TimeoutStrategy
