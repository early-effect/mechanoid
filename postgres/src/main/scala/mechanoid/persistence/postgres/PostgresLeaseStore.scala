package mechanoid.persistence.postgres

import saferis.*
import saferis.postgres.given
import zio.*
import mechanoid.core.{MechanoidError, PersistenceError}
import mechanoid.persistence.timeout.*
import java.time.Instant

/** PostgreSQL implementation of LeaseStore using Saferis.
  *
  * Uses atomic INSERT ... ON CONFLICT for lease acquisition to ensure exactly-one-leader semantics in distributed
  * environments.
  */
class PostgresLeaseStore(transactor: Transactor) extends LeaseStore:

  override def tryAcquire(
      key: String,
      holder: String,
      duration: Duration,
      now: Instant,
  ): ZIO[Any, MechanoidError, Option[Lease]] =
    val expiresAt = now.plusMillis(duration.toMillis)
    val row       = LeaseRow(key, holder, expiresAt, now)
    transactor
      .run {
        Upsert[LeaseRow]
          .values(row)
          .onConflict(_.key)
          .doUpdateAll
          .where(_.expiresAt)
          .lt(now)
          .or(_.holder)
          .eqExcluded
          .returning
          .queryOne
      }
      .map(_.map(rowToLease))
      .mapError(PersistenceError.fromError)
  end tryAcquire

  override def renew(
      key: String,
      holder: String,
      duration: Duration,
      now: Instant,
  ): ZIO[Any, MechanoidError, Boolean] =
    val newExpiry = now.plusMillis(duration.toMillis)
    transactor
      .run {
        Update[LeaseRow]
          .set(_.expiresAt, newExpiry)
          .where(_.key)
          .eq(key)
          .where(_.holder)
          .eq(holder)
          .where(_.expiresAt)
          .gt(now)
          .build
          .dml
      }
      .map(_ > 0)
      .mapError(PersistenceError.fromError)
  end renew

  override def release(key: String, holder: String): ZIO[Any, MechanoidError, Boolean] =
    transactor
      .run {
        Delete[LeaseRow]
          .where(_.key)
          .eq(key)
          .where(_.holder)
          .eq(holder)
          .build
          .dml
      }
      .map(_ > 0)
      .mapError(PersistenceError.fromError)

  override def get(key: String): ZIO[Any, MechanoidError, Option[Lease]] =
    transactor
      .run {
        Query[LeaseRow]
          .where(_.key)
          .eq(key)
          .queryOne[LeaseRow]
      }
      .map(_.map(rowToLease))
      .mapError(PersistenceError.fromError)

  private def rowToLease(row: LeaseRow): Lease =
    Lease(
      key = row.key,
      holder = row.holder,
      expiresAt = row.expiresAt,
      acquiredAt = row.acquiredAt,
    )
end PostgresLeaseStore

object PostgresLeaseStore:
  val layer: ZLayer[Transactor, Nothing, LeaseStore] =
    ZLayer.fromFunction(new PostgresLeaseStore(_))
