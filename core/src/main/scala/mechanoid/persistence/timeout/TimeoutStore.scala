package mechanoid.persistence.timeout

import zio.*
import java.time.Instant
import mechanoid.core.MechanoidError

/** Abstract storage trait for durable timeouts in distributed environments.
  *
  * Implement this trait to persist timeout deadlines to your chosen database (PostgreSQL, DynamoDB, Cassandra, etc.).
  * Combined with a [[TimeoutSweeper]], this enables timeouts that survive node failures.
  *
  * ==Why Separate from EventStore?==
  *
  * This trait is intentionally separate from [[mechanoid.persistence.EventStore]]. Timeouts have different access
  * patterns:
  *
  *   - '''EventStore''': Append-only log, queried by instance ID
  *   - '''TimeoutStore''': Indexed by deadline, requires atomic claims, frequently updated
  *
  * ==Implementation Requirements==
  *
  * '''CRITICAL''': The [[claim]] method MUST implement atomic claim-or-fail semantics. Without this, multiple sweeper
  * nodes could fire the same timeout.
  *
  * ==Recommended Schema (PostgreSQL)==
  *
  * {{{
  * CREATE TABLE scheduled_timeouts (
  *   instance_id    TEXT NOT NULL,
  *   timeout_key    TEXT NOT NULL,
  *   state_hash     INT NOT NULL,
  *   sequence_nr    BIGINT NOT NULL,
  *   deadline       TIMESTAMPTZ NOT NULL,
  *   created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  *   claimed_by     TEXT,
  *   claimed_until  TIMESTAMPTZ,
  *   PRIMARY KEY (instance_id, timeout_key)
  * );
  *
  * CREATE INDEX idx_timeouts_deadline ON scheduled_timeouts (deadline)
  *   WHERE claimed_by IS NULL OR claimed_until < NOW();
  * }}}
  *
  * @tparam Id
  *   The FSM instance identifier type (e.g., UUID, String, Long)
  */
trait TimeoutStore[Id]:

  /** Schedule a named timeout for an FSM instance.
    *
    * Called when the FSM enters a leaf (or Stay re-arms one name). If a row already exists for `(instanceId, name)`, it
    * MUST be replaced (upsert). Other names on the same instance are left alone.
    *
    * @param instanceId
    *   The FSM instance identifier
    * @param name
    *   Timeout key (generated event name, or an explicit name)
    * @param stateHash
    *   Hash of the leaf the FSM should be in when this timeout fires
    * @param sequenceNr
    *   The sequence number when the timeout was scheduled (diagnostics; Stay re-arm uses the new seq)
    * @param deadline
    *   When the timeout should fire
    */
  def schedule(
      instanceId: Id,
      name: String,
      stateHash: Int,
      sequenceNr: Long,
      deadline: Instant,
  ): ZIO[Any, MechanoidError, ScheduledTimeout[Id]]

  /** Cancel every timeout for an FSM instance.
    *
    * Called on Goto away and Stop. No-op if none exist.
    *
    * @return
    *   true if at least one timeout was cancelled
    */
  def cancel(instanceId: Id): ZIO[Any, MechanoidError, Boolean]

  /** Cancel one named timeout. No-op if that name is not armed. */
  def cancel(instanceId: Id, name: String): ZIO[Any, MechanoidError, Boolean]

  /** Query expired timeouts that are not currently claimed.
    *
    * Returns timeouts where:
    * {{{
    * deadline <= now AND (claimed_by IS NULL OR claimed_until < now)
    * }}}
    *
    * Results should be ordered by deadline (oldest first). May return several rows per instance.
    *
    * @param limit
    *   Maximum number of timeouts to return (batch size)
    * @param now
    *   The current timestamp (passed explicitly for testability)
    */
  def queryExpired(
      limit: Int,
      now: Instant,
  ): ZIO[Any, MechanoidError, List[ScheduledTimeout[Id]]]

  /** Atomically claim one named timeout for processing.
    *
    * '''CRITICAL''': This MUST be atomic. Use optimistic locking or database-level atomicity (e.g.,
    * `UPDATE ... WHERE claimed_by IS NULL RETURNING *`).
    */
  def claim(
      instanceId: Id,
      name: String,
      nodeId: String,
      claimDuration: Duration,
      now: Instant,
  ): ZIO[Any, MechanoidError, ClaimResult]

  /** Complete (remove) a named timeout after successful processing.
    *
    * Only deletes if `sequenceNr` matches, so a Stay re-arm of the same name is not deleted.
    */
  def complete(instanceId: Id, name: String, sequenceNr: Long): ZIO[Any, MechanoidError, Boolean]

  /** Release a claim without completing. */
  def release(instanceId: Id, name: String): ZIO[Any, MechanoidError, Boolean]

  /** All timeouts currently armed for an instance. */
  def get(instanceId: Id): ZIO[Any, MechanoidError, Chunk[ScheduledTimeout[Id]]]

  /** One named timeout for an instance, if armed. */
  def get(instanceId: Id, name: String): ZIO[Any, MechanoidError, Option[ScheduledTimeout[Id]]]
end TimeoutStore
