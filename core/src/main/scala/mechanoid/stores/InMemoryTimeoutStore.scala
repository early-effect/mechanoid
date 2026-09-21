package mechanoid.stores

import zio.*
import mechanoid.core.*
import mechanoid.persistence.timeout.*
import java.time.Instant

/** ZIO Ref-based in-memory TimeoutStore implementation.
  *
  * Thread-safe via ZIO Refs. Suitable for testing and simple single-process deployments. For distributed deployments,
  * use a database-backed implementation like PostgresTimeoutStore.
  *
  * ==Usage==
  * {{{
  * for
  *   store <- InMemoryTimeoutStore.make[String]
  *   _     <- store.schedule("instance-1", "PaymentTimeout", stateHash, sequenceNr, deadline)
  * yield ()
  * }}}
  */
final class InMemoryTimeoutStore[Id] private (
    timeoutsRef: Ref[Map[(Id, String), ScheduledTimeout[Id]]]
) extends TimeoutStore[Id]:

  override def schedule(
      instanceId: Id,
      name: String,
      stateHash: Int,
      sequenceNr: Long,
      deadline: Instant,
  ): ZIO[Any, MechanoidError, ScheduledTimeout[Id]] =
    for
      now <- Clock.instant
      timeout = ScheduledTimeout(
        instanceId = instanceId,
        name = name,
        stateHash = stateHash,
        sequenceNr = sequenceNr,
        deadline = deadline,
        createdAt = now,
      )
      _ <- timeoutsRef.update(_ + ((instanceId, name) -> timeout))
    yield timeout

  override def cancel(instanceId: Id): ZIO[Any, MechanoidError, Boolean] =
    timeoutsRef.modify { timeouts =>
      val remaining = timeouts.filterNot(_._1._1 == instanceId)
      (remaining.size < timeouts.size, remaining)
    }

  override def cancel(instanceId: Id, name: String): ZIO[Any, MechanoidError, Boolean] =
    timeoutsRef.modify { timeouts =>
      val existed = timeouts.contains((instanceId, name))
      (existed, timeouts - ((instanceId, name)))
    }

  override def queryExpired(
      limit: Int,
      now: Instant,
  ): ZIO[Any, MechanoidError, List[ScheduledTimeout[Id]]] =
    timeoutsRef.get.map { timeouts =>
      timeouts.values
        .filter(_.canBeClaimed(now))
        .toList
        .sortBy(_.deadline)
        .take(limit)
    }

  override def claim(
      instanceId: Id,
      name: String,
      nodeId: String,
      claimDuration: Duration,
      now: Instant,
  ): ZIO[Any, MechanoidError, ClaimResult] =
    timeoutsRef.modify { timeouts =>
      timeouts.get((instanceId, name)) match
        case None =>
          (ClaimResult.NotFound, timeouts)

        case Some(t) if !t.isExpired(now) =>
          (ClaimResult.NotDue, timeouts)

        case Some(t) if t.isClaimed(now) =>
          (ClaimResult.AlreadyClaimed(t.claimedBy.get, t.claimedUntil.get), timeouts)

        case Some(t) =>
          val claimed = t.copy(
            claimedBy = Some(nodeId),
            claimedUntil = Some(now.plusMillis(claimDuration.toMillis)),
          )
          (ClaimResult.Claimed(claimed), timeouts + ((instanceId, name) -> claimed))
    }

  override def complete(instanceId: Id, name: String, sequenceNr: Long): ZIO[Any, MechanoidError, Boolean] =
    timeoutsRef.modify { timeouts =>
      timeouts.get((instanceId, name)) match
        case Some(t) if t.sequenceNr == sequenceNr =>
          (true, timeouts - ((instanceId, name)))
        case _ =>
          (false, timeouts)
    }

  override def release(instanceId: Id, name: String, nodeId: String): ZIO[Any, MechanoidError, Boolean] =
    timeoutsRef.modify { timeouts =>
      timeouts.get((instanceId, name)) match
        case Some(t) if t.claimedBy.contains(nodeId) =>
          val released = t.copy(claimedBy = None, claimedUntil = None)
          (true, timeouts + ((instanceId, name) -> released))
        case _ =>
          (false, timeouts)
    }

  override def get(instanceId: Id): ZIO[Any, MechanoidError, Chunk[ScheduledTimeout[Id]]] =
    timeoutsRef.get.map { timeouts =>
      Chunk.fromIterable(timeouts.collect { case ((id, _), t) if id == instanceId => t })
    }

  override def get(instanceId: Id, name: String): ZIO[Any, MechanoidError, Option[ScheduledTimeout[Id]]] =
    timeoutsRef.get.map(_.get((instanceId, name)))

  /** Get all timeouts (for testing). */
  def getAll: UIO[Map[(Id, String), ScheduledTimeout[Id]]] =
    timeoutsRef.get

  /** Clear all data (for testing). */
  def clear: UIO[Unit] =
    timeoutsRef.set(Map.empty)

  /** Get the count of scheduled timeouts (for testing). */
  def size: UIO[Int] =
    timeoutsRef.get.map(_.size)
end InMemoryTimeoutStore

object InMemoryTimeoutStore:

  /** Create a new in-memory timeout store. */
  def make[Id]: UIO[InMemoryTimeoutStore[Id]] =
    Ref.make(Map.empty[(Id, String), ScheduledTimeout[Id]]).map(new InMemoryTimeoutStore(_))

  def layer[Id: Tag]: ULayer[TimeoutStore[Id]] =
    ZLayer.fromZIO(make[Id])
end InMemoryTimeoutStore
