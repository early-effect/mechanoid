package mechanoid.persistence.postgres

import saferis.*
import zio.json.*
import java.time.Instant

/** Row model for fsm_events table with properly typed event data. */
@tableName("fsm_events")
final case class EventRow[E](
    @generated @key id: Long,
    @label("instance_id") instanceId: String,
    @label("sequence_nr") sequenceNr: Long,
    @label("event_data") eventData: Json[E],
    @label("created_at") createdAt: Instant,
)

object EventRow:
  given [E: JsonCodec]: Table[EventRow[E]] = Table.derived[EventRow[E]]

/** Row model for fsm_snapshots table with properly typed state data. */
@tableName("fsm_snapshots")
final case class SnapshotRow[S](
    @key @label("instance_id") instanceId: String,
    @label("state_data") stateData: Json[S],
    @label("sequence_nr") sequenceNr: Long,
    @label("created_at") createdAt: Instant,
)

object SnapshotRow:
  given [S: JsonCodec]: Table[SnapshotRow[S]] = Table.derived[SnapshotRow[S]]

/** Row model for scheduled_timeouts table. */
@tableName("scheduled_timeouts")
final case class TimeoutRow(
    @key @label("instance_id") instanceId: String,
    @label("state_hash") stateHash: Int,
    @label("sequence_nr") sequenceNr: Long,
    deadline: Instant,
    @label("created_at") createdAt: Instant,
    @label("claimed_by") claimedBy: Option[String],
    @label("claimed_until") claimedUntil: Option[Instant],
) derives Table

/** Row model for fsm_instance_locks table. */
@tableName("fsm_instance_locks")
final case class LockRow(
    @key @label("instance_id") instanceId: String,
    @label("node_id") nodeId: String,
    @label("acquired_at") acquiredAt: Instant,
    @label("expires_at") expiresAt: Instant,
) derives Table

/** Row model for leases table. */
@tableName("leases")
final case class LeaseRow(
    @key key: String,
    holder: String,
    @label("expires_at") expiresAt: Instant,
    @label("acquired_at") acquiredAt: Instant,
) derives Table
