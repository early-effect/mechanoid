package mechanoid.runtime.timeout

import zio.*

/** In-memory timeout strategy using ZIO fibers.
  *
  * This implementation schedules timeouts as daemon fibers that sleep for the specified duration, then invoke the
  * callback. Timeouts can be cancelled by interrupting the fiber.
  *
  * ==Characteristics==
  *
  *   - '''Fast''': No I/O, pure in-memory
  *   - '''Not durable''': Timeouts don't survive node failures
  *   - '''No coordination''': Each node manages its own timeouts independently
  *
  * ==When to Use==
  *
  *   - Development and testing
  *   - Single-node deployments where durability isn't needed
  *   - When timeout events are idempotent and can be re-triggered on recovery
  *
  * ==Cancellation Semantics==
  *
  * When a new timeout is scheduled, any existing timeout for that instance is automatically cancelled. This handles
  * state transitions cleanly - entering a new state cancels the old state's timeout.
  *
  * @tparam Id
  *   FSM instance identifier type
  */
final class FiberTimeoutStrategy[Id] private (
    fibers: Ref[Map[Id, Fiber.Runtime[Nothing, Unit]]]
) extends TimeoutStrategy[Id]:

  override def schedule(
      instanceId: Id,
      stateHash: Int,
      sequenceNr: Long,
      duration: Duration,
      onTimeout: UIO[Unit],
  ): UIO[Unit] =
    for
      // Cancel any existing timeout first
      _ <- cancel(instanceId)
      // Fork a new timeout fiber (stateHash/sequenceNr not used - we use onTimeout callback directly)
      // Using forkDaemon so timeout fibers survive parent scope closure
      // IMPORTANT: Remove fiber from map BEFORE running callback to prevent self-interruption
      // when the callback triggers a state transition that calls cancelTimeout
      fiber <- (ZIO.sleep(duration) *> fibers.update(_ - instanceId) *> onTimeout).forkDaemon
      // Track the fiber for potential cancellation
      _ <- fibers.update(_ + (instanceId -> fiber))
    yield ()

  override def cancel(instanceId: Id): UIO[Unit] =
    fibers.modify { map =>
      map.get(instanceId) match
        case Some(fiber) => (fiber.interrupt.unit, map - instanceId)
        case None        => (ZIO.unit, map)
    }.flatten

end FiberTimeoutStrategy

object FiberTimeoutStrategy:

  /** Create a new fiber-based timeout strategy.
    *
    * @return
    *   A new strategy instance
    */
  def make[Id]: UIO[FiberTimeoutStrategy[Id]] =
    Ref.make(Map.empty[Id, Fiber.Runtime[Nothing, Unit]]).map(new FiberTimeoutStrategy(_))

  /** Layer providing a fiber-based timeout strategy.
    *
    * @return
    *   A layer that provides `TimeoutStrategy[Id]`
    */
  def layer[Id: Tag]: ULayer[TimeoutStrategy[Id]] =
    ZLayer.fromZIO(make[Id])

end FiberTimeoutStrategy
