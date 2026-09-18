package mechanoid.persistence.timeout

import zio.*
import java.time.Instant
import mechanoid.core.MechanoidError

/** Abstract storage trait for leader election leases.
  *
  * Implement this trait to persist leases to your chosen database. The [[LeaderElection]] service uses this to
  * coordinate which sweeper node is active.
  *
  * ==Implementation Requirements==
  *
  * '''CRITICAL''': The [[tryAcquire]] method MUST be atomic. Use database-level atomicity (e.g.,
  * `INSERT ... ON CONFLICT` or `UPDATE ... WHERE`).
  *
  * ==Recommended Schema (PostgreSQL)==
  *
  * {{{
  * CREATE TABLE leases (
  *   key         TEXT PRIMARY KEY,
  *   holder      TEXT NOT NULL,
  *   expires_at  TIMESTAMPTZ NOT NULL,
  *   acquired_at TIMESTAMPTZ NOT NULL
  * );
  * }}}
  *
  * ==Example PostgreSQL Implementation==
  *
  * {{{
  * class PostgresLeaseStore(xa: Transactor[Task]) extends LeaseStore:
  *
  *   def tryAcquire(key: String, holder: String, duration: Duration, now: Instant) =
  *     val expiresAt = now.plusMillis(duration.toMillis)
  *     sql"""
  *       INSERT INTO leases (key, holder, expires_at, acquired_at)
  *       VALUES (\$key, \$holder, \$expiresAt, \$now)
  *       ON CONFLICT (key) DO UPDATE
  *       SET holder = \$holder, expires_at = \$expiresAt, acquired_at = \$now
  *       WHERE leases.expires_at < \$now OR leases.holder = \$holder
  *       RETURNING *
  *     """.query[Lease].option.transact(xa)
  * }}}
  */
trait LeaseStore:

  /** Try to acquire a lease.
    *
    * Succeeds if:
    *   - No lease exists for this key, OR
    *   - Existing lease has expired, OR
    *   - Existing lease is held by this holder (re-acquire)
    *
    * '''MUST be atomic''' - use database-level atomicity.
    *
    * @param key
    *   The lease key (e.g., "mechanoid-timeout-leader")
    * @param holder
    *   The node ID trying to acquire
    * @param duration
    *   How long the lease should last
    * @param now
    *   The current timestamp
    * @return
    *   Some(lease) if acquired, None if held by another node
    */
  def tryAcquire(
      key: String,
      holder: String,
      duration: Duration,
      now: Instant,
  ): ZIO[Any, MechanoidError, Option[Lease]]

  /** Renew an existing lease.
    *
    * Only succeeds if the lease is currently held by this holder. Extends the expiry time by `duration` from `now`.
    *
    * @param key
    *   The lease key
    * @param holder
    *   The node ID that should hold the lease
    * @param duration
    *   How long to extend the lease
    * @param now
    *   The current timestamp
    * @return
    *   true if renewed, false if lease was lost
    */
  def renew(
      key: String,
      holder: String,
      duration: Duration,
      now: Instant,
  ): ZIO[Any, MechanoidError, Boolean]

  /** Release a lease voluntarily.
    *
    * Called during graceful shutdown to allow faster leader election. No-op if the lease is not held by this holder.
    *
    * @param key
    *   The lease key
    * @param holder
    *   The node ID releasing the lease
    * @return
    *   true if released, false if not held by this holder
    */
  def release(key: String, holder: String): ZIO[Any, MechanoidError, Boolean]

  /** Get the current lease holder (if any).
    *
    * Useful for debugging and monitoring.
    *
    * @param key
    *   The lease key
    * @return
    *   The current lease if one exists
    */
  def get(key: String): ZIO[Any, MechanoidError, Option[Lease]]
end LeaseStore
