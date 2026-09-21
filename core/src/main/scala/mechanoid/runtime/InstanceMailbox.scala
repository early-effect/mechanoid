package mechanoid.runtime

import zio.*

/** In-process exclusivity for one FSM instance id.
  *
  * Waiters line up on a single permit per id (ZIO [[Semaphore]]). Same-node HTTP handlers and the timeout sweeper share
  * one mailbox so reconstruct+send cannot interleave locally. Cross-node exclusivity is still
  * [[mechanoid.runtime.locking.LockingStrategy]].
  *
  * One semaphore is retained per id this node has seen. That is cheaper than a worker fiber per id.
  */
trait InstanceMailbox[Id]:
  def run[R, E, A](id: Id)(effect: ZIO[R, E, A]): ZIO[R, E, A]

object InstanceMailbox:

  def make[Id]: UIO[InstanceMailbox[Id]] =
    Ref.make(Map.empty[Id, Semaphore]).map(Live(_))

  def layer[Id: Tag]: ULayer[InstanceMailbox[Id]] =
    ZLayer.fromZIO(make[Id])

  private final class Live[Id](gates: Ref[Map[Id, Semaphore]]) extends InstanceMailbox[Id]:
    def run[R, E, A](id: Id)(effect: ZIO[R, E, A]): ZIO[R, E, A] =
      gate(id).flatMap(_.withPermit(effect))

    private def gate(id: Id): UIO[Semaphore] =
      Semaphore.make(1).flatMap { created =>
        gates.modify { m =>
          m.get(id) match
            case Some(existing) => (existing, m)
            case None           => (created, m + (id -> created))
        }
      }
  end Live
end InstanceMailbox
