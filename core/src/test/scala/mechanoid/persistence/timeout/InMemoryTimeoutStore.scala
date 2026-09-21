package mechanoid.persistence.timeout

import zio.*
import mechanoid.core.MechanoidError
import java.time.Instant
import scala.collection.mutable

/** In-memory implementation of [[TimeoutStore]] for testing. */
class InMemoryTimeoutStore[Id] extends TimeoutStore[Id]:
  private val timeouts = mutable.Map[(Id, String), ScheduledTimeout[Id]]()

  def schedule(
      instanceId: Id,
      name: String,
      stateHash: Int,
      sequenceNr: Long,
      deadline: Instant,
  ): ZIO[Any, MechanoidError, ScheduledTimeout[Id]] =
    ZIO.succeed {
      synchronized {
        val timeout = ScheduledTimeout(
          instanceId = instanceId,
          name = name,
          stateHash = stateHash,
          sequenceNr = sequenceNr,
          deadline = deadline,
          createdAt = Instant.now(),
        )
        timeouts((instanceId, name)) = timeout
        timeout
      }
    }

  def cancel(instanceId: Id): ZIO[Any, MechanoidError, Boolean] =
    ZIO.succeed {
      synchronized {
        val keys = timeouts.keys.filter(_._1 == instanceId).toList
        keys.foreach(timeouts.remove)
        keys.nonEmpty
      }
    }

  def cancel(instanceId: Id, name: String): ZIO[Any, MechanoidError, Boolean] =
    ZIO.succeed {
      synchronized {
        timeouts.remove((instanceId, name)).isDefined
      }
    }

  def queryExpired(
      limit: Int,
      now: Instant,
  ): ZIO[Any, MechanoidError, List[ScheduledTimeout[Id]]] =
    ZIO.succeed {
      synchronized {
        timeouts.values
          .filter(_.canBeClaimed(now))
          .toList
          .sortBy(_.deadline)
          .take(limit)
      }
    }

  def claim(
      instanceId: Id,
      name: String,
      nodeId: String,
      claimDuration: Duration,
      now: Instant,
  ): ZIO[Any, MechanoidError, ClaimResult] =
    ZIO.succeed {
      synchronized {
        timeouts.get((instanceId, name)) match
          case None =>
            ClaimResult.NotFound

          case Some(t) if !t.isExpired(now) =>
            ClaimResult.NotDue

          case Some(t) if t.isClaimed(now) =>
            ClaimResult.AlreadyClaimed(t.claimedBy.get, t.claimedUntil.get)

          case Some(t) =>
            val claimed = t.copy(
              claimedBy = Some(nodeId),
              claimedUntil = Some(now.plusMillis(claimDuration.toMillis)),
            )
            timeouts((instanceId, name)) = claimed
            ClaimResult.Claimed(claimed)
      }
    }

  def complete(instanceId: Id, name: String, sequenceNr: Long): ZIO[Any, MechanoidError, Boolean] =
    ZIO.succeed {
      synchronized {
        timeouts.get((instanceId, name)) match
          case Some(t) if t.sequenceNr == sequenceNr =>
            timeouts.remove((instanceId, name))
            true
          case _ =>
            false
      }
    }

  def release(instanceId: Id, name: String, nodeId: String): ZIO[Any, MechanoidError, Boolean] =
    ZIO.succeed {
      synchronized {
        timeouts.get((instanceId, name)) match
          case Some(t) if t.claimedBy.contains(nodeId) =>
            timeouts((instanceId, name)) = t.copy(claimedBy = None, claimedUntil = None)
            true
          case _ =>
            false
      }
    }

  def get(instanceId: Id): ZIO[Any, MechanoidError, Chunk[ScheduledTimeout[Id]]] =
    ZIO.succeed {
      synchronized {
        Chunk.fromIterable(timeouts.collect { case ((id, _), t) if id == instanceId => t })
      }
    }

  def get(instanceId: Id, name: String): ZIO[Any, MechanoidError, Option[ScheduledTimeout[Id]]] =
    ZIO.succeed {
      synchronized {
        timeouts.get((instanceId, name))
      }
    }

  def getAll: Map[(Id, String), ScheduledTimeout[Id]] =
    synchronized {
      timeouts.toMap
    }

  def clear(): Unit =
    synchronized {
      timeouts.clear()
    }

  def size: Int =
    synchronized {
      timeouts.size
    }
end InMemoryTimeoutStore
