package mechanoid.runtime.timeout

import java.time.Instant
import zio.*
import mechanoid.core.MechanoidError

/** In-memory timeout strategy using ZIO fibers.
  *
  * Timeouts are daemon fibers keyed by `(instanceId, name)`. Scheduling one name does not cancel the others.
  *
  * @tparam Id
  *   FSM instance identifier type
  */
final class FiberTimeoutStrategy[Id] private (
    fibers: Ref[Map[Id, Map[String, Fiber.Runtime[Nothing, Unit]]]]
) extends TimeoutStrategy[Id]:

  override def schedule(
      instanceId: Id,
      name: String,
      stateHash: Int,
      sequenceNr: Long,
      deadline: Instant,
      onTimeout: UIO[Unit],
  ): UIO[Unit] =
    for
      _     <- cancel(instanceId, name)
      fiber <- (sleepUntil(deadline) *> drop(instanceId, name) *> onTimeout).forkDaemon
      _     <- fibers.update { map =>
        val inner = map.getOrElse(instanceId, Map.empty) + (name -> fiber)
        map + (instanceId -> inner)
      }
    yield ()

  override def cancel(instanceId: Id): UIO[Unit] =
    fibers.modify { map =>
      map.get(instanceId) match
        case Some(inner) => (ZIO.foreachDiscard(inner.values)(_.interrupt.unit), map - instanceId)
        case None        => (ZIO.unit, map)
    }.flatten

  override def cancel(instanceId: Id, name: String): UIO[Unit] =
    fibers.modify { map =>
      map.get(instanceId).flatMap(_.get(name)) match
        case Some(fiber) =>
          val inner = map(instanceId) - name
          val next  = if inner.isEmpty then map - instanceId else map + (instanceId -> inner)
          (fiber.interrupt.unit, next)
        case None =>
          (ZIO.unit, map)
    }.flatten

  override def purge(instanceId: Id): ZIO[Any, MechanoidError, Unit] =
    cancel(instanceId)

  override def retain(instanceId: Id, keep: Set[String]): UIO[Unit] =
    fibers.get.flatMap { map =>
      val names = map.getOrElse(instanceId, Map.empty).keySet
      ZIO.foreachDiscard(names.diff(keep))(cancel(instanceId, _))
    }

  private def drop(instanceId: Id, name: String): UIO[Unit] =
    fibers.update { map =>
      map.get(instanceId) match
        case Some(inner) =>
          val next = inner - name
          if next.isEmpty then map - instanceId else map + (instanceId -> next)
        case None => map
    }

  private def sleepUntil(deadline: Instant): UIO[Unit] =
    Clock.instant.flatMap { now =>
      val remaining = java.time.Duration.between(now, deadline)
      if remaining.isNegative || remaining.isZero then ZIO.unit
      else ZIO.sleep(Duration.fromNanos(remaining.toNanos))
    }

end FiberTimeoutStrategy

object FiberTimeoutStrategy:

  def make[Id]: UIO[FiberTimeoutStrategy[Id]] =
    Ref.make(Map.empty[Id, Map[String, Fiber.Runtime[Nothing, Unit]]]).map(new FiberTimeoutStrategy(_))

  def layer[Id: Tag]: ULayer[TimeoutStrategy[Id]] =
    ZLayer.fromZIO(make[Id])

end FiberTimeoutStrategy
