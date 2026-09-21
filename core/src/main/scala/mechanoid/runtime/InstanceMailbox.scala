package mechanoid.runtime

import zio.*

/** In-process exclusivity for one FSM instance id.
  *
  * Waiters line up on a single permit per id (ZIO [[Semaphore]]). Same-node HTTP handlers and the timeout sweeper share
  * one mailbox so reconstruct+send cannot interleave locally. Cross-node exclusivity is still
  * [[mechanoid.runtime.locking.LockingStrategy]].
  *
  * A gate is created the first time an id is seen. A repeat call reuses it. Idle gates above `maxIdle` are dropped so a
  * long-lived node does not keep one semaphore for every id it has ever touched. A gate with a caller in `run` is not
  * dropped.
  */
trait InstanceMailbox[Id]:
  def run[R, E, A](id: Id)(effect: ZIO[R, E, A]): ZIO[R, E, A]

object InstanceMailbox:

  /** Idle gates kept after the ones in use. Active gates are extra and are not evicted. */
  val DefaultMaxIdle: Int = 1024

  def make[Id]: UIO[InstanceMailbox[Id]] =
    make(DefaultMaxIdle)

  def make[Id](maxIdle: Int): UIO[InstanceMailbox[Id]] =
    makeCounted(maxIdle)

  def layer[Id: Tag]: ULayer[InstanceMailbox[Id]] =
    ZLayer.fromZIO(make[Id].map[InstanceMailbox[Id]](identity))

  private[runtime] trait Counted[Id] extends InstanceMailbox[Id]:
    def cachedCount: UIO[Int]

  private[runtime] def makeCounted[Id](maxIdle: Int): UIO[Counted[Id]] =
    Ref.make(Map.empty[Id, Gate]).map(Live(_, maxIdle))

  private final case class Gate(semaphore: Semaphore, users: Int)

  private final class Live[Id](gates: Ref[Map[Id, Gate]], maxIdle: Int) extends Counted[Id]:
    def cachedCount: UIO[Int] = gates.get.map(_.size)

    def run[R, E, A](id: Id)(effect: ZIO[R, E, A]): ZIO[R, E, A] =
      acquire(id).flatMap { semaphore =>
        semaphore.withPermit(effect).ensuring(release(id, semaphore))
      }

    private def acquire(id: Id): UIO[Semaphore] =
      gates
        .modify { m =>
          m.get(id) match
            case Some(gate) => (Some(gate.semaphore), m.updated(id, gate.copy(users = gate.users + 1)))
            case None       => (None, m)
        }
        .flatMap {
          case Some(semaphore) => ZIO.succeed(semaphore)
          case None            =>
            Semaphore.make(1).flatMap { created =>
              gates.modify { m =>
                m.get(id) match
                  case Some(gate) => (gate.semaphore, m.updated(id, gate.copy(users = gate.users + 1)))
                  case None       => (created, m.updated(id, Gate(created, 1)))
              }
            }
        }

    private def release(id: Id, semaphore: Semaphore): UIO[Unit] =
      gates.update { m =>
        m.get(id) match
          case Some(gate) if gate.semaphore eq semaphore =>
            shrink(m.updated(id, gate.copy(users = gate.users - 1)))
          case _ =>
            m
      }

    private def shrink(m: Map[Id, Gate]): Map[Id, Gate] =
      if m.size <= maxIdle then m
      else
        val idle = m.collect { case (idleId, gate) if gate.users <= 0 => idleId }
        m -- idle.take(m.size - maxIdle)
  end Live
end InstanceMailbox
