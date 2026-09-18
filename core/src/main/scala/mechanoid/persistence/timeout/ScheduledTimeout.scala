package mechanoid.persistence.timeout

import java.time.Instant

/** A persisted timeout record for durable timeout handling.
  *
  * Unlike in-memory fiber-based timeouts, `ScheduledTimeout` survives node failures by persisting the deadline to a
  * database. A background sweeper process queries for expired timeouts and fires them.
  *
  * ==Named rows==
  *
  * An instance may have several timeouts at once. Identity is `(instanceId, name)` where `name` is the timeout key
  * (generated from the event case, or supplied). Scheduling the same name replaces that row; other names on the
  * instance stay armed.
  *
  * ==State validation==
  *
  * `stateHash` is the leaf the FSM should still be in when this timeout fires. The sweeper skips the row when the
  * current leaf no longer declares this name.
  *
  * ==Claim mechanism==
  *
  * In distributed deployments, multiple sweeper nodes may discover the same expired timeout. The [[claimedBy]] and
  * [[claimedUntil]] fields implement distributed coordination:
  *
  *   - '''Unclaimed''': `claimedBy = None` - any sweeper can claim it
  *   - '''Claimed''': `claimedBy = Some(nodeId)` with `claimedUntil` in the future
  *   - '''Expired claim''': `claimedUntil` in the past - can be re-claimed
  *
  * @tparam Id
  *   The FSM instance identifier type
  */
final case class ScheduledTimeout[Id](
    instanceId: Id,
    name: String,
    stateHash: Int,
    sequenceNr: Long,
    deadline: Instant,
    createdAt: Instant,
    claimedBy: Option[String] = None,
    claimedUntil: Option[Instant] = None,
):
  /** Check if this timeout is currently claimed by a node. */
  def isClaimed(now: Instant): Boolean =
    claimedBy.isDefined && claimedUntil.exists(_.isAfter(now))

  /** Check if this timeout has expired and is ready to fire. */
  def isExpired(now: Instant): Boolean =
    deadline.isBefore(now) || deadline == now

  /** Check if this timeout can be claimed (expired deadline and not currently claimed). */
  def canBeClaimed(now: Instant): Boolean =
    isExpired(now) && !isClaimed(now)
end ScheduledTimeout
