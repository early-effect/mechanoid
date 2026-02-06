package mechanoid.persistence.postgres

import saferis.*
import saferis.postgres.given
import zio.*
import mechanoid.core.{MechanoidError, PersistenceError}
import mechanoid.persistence.timeout.*
import java.time.Instant

/** PostgreSQL implementation of TimeoutStore using Saferis.
  *
  * This implementation uses atomic UPDATE ... RETURNING for claim operations to ensure exactly-once timeout processing
  * in distributed environments.
  */
class PostgresTimeoutStore(transactor: Transactor) extends TimeoutStore[String]:

  private val timeouts = Table[TimeoutRow]

  override def schedule(
      instanceId: String,
      stateHash: Int,
      sequenceNr: Long,
      deadline: Instant,
  ): ZIO[Any, MechanoidError, ScheduledTimeout[String]] =
    (for
      now <- Clock.instant
      row = TimeoutRow(instanceId, stateHash, sequenceNr, deadline, now, None, None)
      _ <- transactor.run {
        Upsert[TimeoutRow]
          .values(row)
          .onConflict(_.instanceId)
          .doUpdateAll
          .build
          .dml
      }
    yield ScheduledTimeout(instanceId, stateHash, sequenceNr, deadline, now, None, None))
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
          .where(_.instanceId)
          .eq(instanceId)
          .andWhere(w => w(_.claimedBy).isNull.or(_.claimedUntil).lt(Some(now)))
          .returningAs
          .queryOne
      }
      .flatMap {
        case Some(row) =>
          ZIO.succeed(ClaimResult.Claimed(rowToTimeout(row)))
        case None =>
          // Check if it exists but is claimed, or doesn't exist
          get(instanceId).map {
            case Some(timeout) if timeout.isClaimed(now) =>
              ClaimResult.AlreadyClaimed(timeout.claimedBy.getOrElse("unknown"), timeout.claimedUntil.getOrElse(now))
            case Some(_) =>
              // Exists but not claimed - race condition, treat as already claimed
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

  override def complete(instanceId: String, sequenceNr: Long): ZIO[Any, MechanoidError, Boolean] =
    transactor
      .run {
        Delete[TimeoutRow]
          .where(_.instanceId)
          .eq(instanceId)
          .where(_.sequenceNr)
          .eq(sequenceNr)
          .build
          .dml
      }
      .map(_ > 0)
      .mapError(PersistenceError.fromError)

  override def release(instanceId: String): ZIO[Any, MechanoidError, Boolean] =
    transactor
      .run {
        Update[TimeoutRow]
          .set(_.claimedBy, Option.empty[String])
          .set(_.claimedUntil, Option.empty[Instant])
          .where(_.instanceId)
          .eq(instanceId)
          .build
          .dml
      }
      .map(_ > 0)
      .mapError(PersistenceError.fromError)

  override def get(instanceId: String): ZIO[Any, MechanoidError, Option[ScheduledTimeout[String]]] =
    transactor
      .run {
        Query[TimeoutRow]
          .where(_.instanceId)
          .eq(instanceId)
          .queryOne[TimeoutRow]
      }
      .map(_.map(rowToTimeout))
      .mapError(PersistenceError.fromError)

  private def rowToTimeout(row: TimeoutRow): ScheduledTimeout[String] =
    ScheduledTimeout(
      instanceId = row.instanceId,
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
