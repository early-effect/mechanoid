package mechanoid.runtime.timeout

import java.time.Instant
import zio.*
import mechanoid.core.MechanoidError
import mechanoid.persistence.timeout.TimeoutStore

/** Durable timeout strategy that persists deadlines to a [[TimeoutStore]].
  *
  * When [[schedule]] is called for the same `(name, stateHash)` already in the store (typical after reconstructing an
  * [[mechanoid.runtime.FSMRuntime]]), the existing absolute deadline is left alone so recover does not reset the timer.
  *
  * The `onTimeout` callback is unused; the sweeper is responsible for firing.
  */
final class DurableTimeoutStrategy[Id] private (
    timeoutStore: TimeoutStore[Id]
) extends TimeoutStrategy[Id]:

  override def schedule(
      instanceId: Id,
      name: String,
      stateHash: Int,
      sequenceNr: Long,
      deadline: Instant,
      onTimeout: UIO[Unit],
  ): UIO[Unit] =
    timeoutStore.get(instanceId, name).orElseSucceed(None).flatMap {
      case Some(t) if t.stateHash == stateHash =>
        ZIO.unit
      case _ =>
        timeoutStore.schedule(instanceId, name, stateHash, sequenceNr, deadline).ignore
    }

  override def cancel(instanceId: Id): UIO[Unit] =
    timeoutStore.cancel(instanceId).ignore

  override def cancel(instanceId: Id, name: String): UIO[Unit] =
    timeoutStore.cancel(instanceId, name).ignore

  override def purge(instanceId: Id): ZIO[Any, MechanoidError, Unit] =
    timeoutStore.cancel(instanceId).unit

  override def retain(instanceId: Id, keep: Set[String]): UIO[Unit] =
    timeoutStore.get(instanceId).orElseSucceed(Chunk.empty).flatMap { rows =>
      ZIO.foreachDiscard(rows.filterNot(t => keep.contains(t.name))) { t =>
        timeoutStore.cancel(instanceId, t.name).ignore
      }
    }
end DurableTimeoutStrategy

object DurableTimeoutStrategy:

  def make[Id](timeoutStore: TimeoutStore[Id]): DurableTimeoutStrategy[Id] =
    new DurableTimeoutStrategy(timeoutStore)

  def layer[Id: Tag]: URLayer[TimeoutStore[Id], TimeoutStrategy[Id]] =
    ZLayer {
      ZIO.service[TimeoutStore[Id]].map(make)
    }
end DurableTimeoutStrategy
