package mechanoid.persistence.postgres

import saferis.*
import saferis.postgres.given
import zio.*
import mechanoid.core.{MechanoidError, PersistenceError}
import mechanoid.persistence.timeout.*
import java.time.Instant

/** PostgreSQL implementation of TimeoutStore using Saferis.
  *
  * Atomic UPDATE ... RETURNING for claim operations, keyed by `(instance_id, timeout_key)`.
  */
class PostgresTimeoutStore(transactor: Transactor) extends TimeoutStore[String]:

  private val timeouts = Table[TimeoutRow]

  override def schedule(
      instanceId: String,
      name: String,
      stateHash: Int,
      sequenceNr: Long,
      deadline: Instant,
  ): ZIO[Any, MechanoidError, ScheduledTimeout[String]] =
    (for
      now <- Clock.instant
      row = TimeoutRow(instanceId, name, stateHash, sequenceNr, deadline, now, None, None)
      _ <- transactor.run {
        Upsert[TimeoutRow]
          .values(row)
          .onConflict(_.instanceId)
          .and(_.timeoutKey)
          .doUpdateAll
          .build
          .dml
      }
    yield ScheduledTimeout(instanceId, name, stateHash, sequenceNr, deadline, now, None, None))
      .mapError(PersistenceError.fromError)

  override def cancel(instanceId: String): ZIO[Any, MechanoidError, Boolean] =
    transactor
      .run {
        Delete[TimeoutRow]
          .where(_.instanceId)
          .eq(instanceId)
          .build
          .dml
      }
      .map(_ > 0)
      .mapError(PersistenceError.fromError)

  override def cancel(instanceId: String, name: String): ZIO[Any, MechanoidError, Boolean] =
    transactor
      .run {
        Delete[TimeoutRow]
          .where(_.instanceId)
          .eq(instanceId)
          .where(_.timeoutKey)
          .eq(name)
          .build
          .dml
      }
      .map(_ > 0)
      .mapError(PersistenceError.fromError)

  override def queryExpired(limit: Int, now: Instant): ZIO[Any, MechanoidError, List[ScheduledTimeout[String]]] =
    transactor
      .run {
        Query[TimeoutRow]
          .where(_.deadline)
          .lte(now)
          .andWhere(w => w(_.claimedBy).isNull.or(_.claimedUntil).lt(Some(now)))
          .orderBy(timeouts.deadline.asc)
          .limit(limit)
          .query[TimeoutRow]
      }
      .map(_.map(rowToTimeout).toList)
      .mapError(PersistenceError.fromError)

  override def claim(
      instanceId: String,
      name: String,
      nodeId: String,
      claimDuration: Duration,
      now: Instant,
  ): ZIO[Any, MechanoidError, ClaimResult] =
    val claimedUntil = now.plusMillis(claimDuration.toMillis)
    transactor
      .run {
        Update[TimeoutRow]
          .set(_.claimedBy, Some(nodeId))
          .set(_.claimedUntil, Some(claimedUntil))
          .where(_.deadline)
          .lte(now)
          .where(_.instanceId)
          .eq(instanceId)
          .where(_.timeoutKey)
          .eq(name)
          .andWhere(w => w(_.claimedBy).isNull.or(_.claimedUntil).lt(Some(now)))
          .returningAs
          .queryOne
      }
      .flatMap {
        case Some(row) =>
          ZIO.succeed(ClaimResult.Claimed(rowToTimeout(row)))
        case None =>
          get(instanceId, name).map {
            case Some(timeout) if !timeout.isExpired(now) =>
              ClaimResult.NotDue
            case Some(timeout) if timeout.isClaimed(now) =>
              ClaimResult.AlreadyClaimed(timeout.claimedBy.getOrElse("unknown"), timeout.claimedUntil.getOrElse(now))
            case Some(_) =>
              ClaimResult.AlreadyClaimed("unknown", now)
            case None =>
              ClaimResult.NotFound
          }
      }
      .mapError {
        case e: MechanoidError => e
        case e                 => PersistenceError.fromError(e)
      }
  end claim

  override def complete(instanceId: String, name: String, sequenceNr: Long): ZIO[Any, MechanoidError, Boolean] =
    transactor
      .run {
        Delete[TimeoutRow]
          .where(_.instanceId)
          .eq(instanceId)
          .where(_.timeoutKey)
          .eq(name)
          .where(_.sequenceNr)
          .eq(sequenceNr)
          .build
          .dml
      }
      .map(_ > 0)
      .mapError(PersistenceError.fromError)

  override def release(instanceId: String, name: String, nodeId: String): ZIO[Any, MechanoidError, Boolean] =
    transactor
      .run {
        Update[TimeoutRow]
          .set(_.claimedBy, Option.empty[String])
          .set(_.claimedUntil, Option.empty[Instant])
          .where(_.instanceId)
          .eq(instanceId)
          .where(_.timeoutKey)
          .eq(name)
          .where(_.claimedBy)
          .eq(Some(nodeId))
          .build
          .dml
      }
      .map(_ > 0)
      .mapError(PersistenceError.fromError)

  override def get(instanceId: String): ZIO[Any, MechanoidError, Chunk[ScheduledTimeout[String]]] =
    transactor
      .run {
        Query[TimeoutRow]
          .where(_.instanceId)
          .eq(instanceId)
          .query[TimeoutRow]
      }
      .map(rows => Chunk.fromIterable(rows.map(rowToTimeout)))
      .mapError(PersistenceError.fromError)

  override def get(instanceId: String, name: String): ZIO[Any, MechanoidError, Option[ScheduledTimeout[String]]] =
    transactor
      .run {
        Query[TimeoutRow]
          .where(_.instanceId)
          .eq(instanceId)
          .where(_.timeoutKey)
          .eq(name)
          .queryOne[TimeoutRow]
      }
      .map(_.map(rowToTimeout))
      .mapError(PersistenceError.fromError)

  private def rowToTimeout(row: TimeoutRow): ScheduledTimeout[String] =
    ScheduledTimeout(
      instanceId = row.instanceId,
      name = row.timeoutKey,
      stateHash = row.stateHash,
      sequenceNr = row.sequenceNr,
      deadline = row.deadline,
      createdAt = row.createdAt,
      claimedBy = row.claimedBy,
      claimedUntil = row.claimedUntil,
    )
end PostgresTimeoutStore

object PostgresTimeoutStore:
  val layer: ZLayer[Transactor, Nothing, TimeoutStore[String]] =
    ZLayer.fromFunction(new PostgresTimeoutStore(_))
