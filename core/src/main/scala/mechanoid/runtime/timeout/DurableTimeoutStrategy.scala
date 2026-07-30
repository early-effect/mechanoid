package mechanoid.runtime.timeout

import zio.*
import mechanoid.persistence.timeout.TimeoutStore

/** Durable timeout strategy that persists deadlines to a [[TimeoutStore]].
  *
  * Schedules timeouts by writing deadline records to persistent storage. A separate
  * [[mechanoid.persistence.timeout.TimeoutSweeper]] process polls for expired deadlines and fires the timeout events.
  *
  * When [[schedule]] is called for the same `(stateHash, sequenceNr)` already in the store (typical after
  * reconstructing an [[mechanoid.runtime.FSMRuntime]]), the existing absolute deadline is left alone so recover does
  * not reset the timer.
  *
  * The `onTimeout` callback is unused; the sweeper is responsible for firing.
  *
  * @tparam Id
  *   FSM instance identifier type
  */
final class DurableTimeoutStrategy[Id] private (
    timeoutStore: TimeoutStore[Id]
) extends TimeoutStrategy[Id]:

  override def schedule(
      instanceId: Id,
      stateHash: Int,
      sequenceNr: Long,
      duration: Duration,
      onTimeout: UIO[Unit],
  ): UIO[Unit] =
    for
      now      <- Clock.instant
      existing <- timeoutStore.get(instanceId).orElseSucceed(None)
      _        <- existing match
        case Some(t) if t.stateHash == stateHash && t.sequenceNr == sequenceNr =>
          ZIO.unit
        case _ =>
          val deadline = now.plusMillis(duration.toMillis)
          timeoutStore.schedule(instanceId, stateHash, sequenceNr, deadline).ignore
    yield ()

  override def cancel(instanceId: Id): UIO[Unit] =
    timeoutStore.cancel(instanceId).ignore
end DurableTimeoutStrategy

object DurableTimeoutStrategy:

  /** Create a durable timeout strategy backed by a TimeoutStore. */
  def make[Id](timeoutStore: TimeoutStore[Id]): DurableTimeoutStrategy[Id] =
    new DurableTimeoutStrategy(timeoutStore)

  /** Layer providing a durable timeout strategy.
    *
    * Requires `TimeoutStore[Id]` in the environment.
    */
  def layer[Id: Tag]: URLayer[TimeoutStore[Id], TimeoutStrategy[Id]] =
    ZLayer {
      ZIO.service[TimeoutStore[Id]].map(make)
    }
end DurableTimeoutStrategy
