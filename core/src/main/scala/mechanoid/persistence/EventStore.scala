package mechanoid.persistence

import zio.*
import zio.stream.*
import mechanoid.core.*
import scala.annotation.unused

/** Abstract storage trait for event sourcing with distributed safety guarantees.
  *
  * Implement this trait to persist FSM state using your chosen database (PostgreSQL, DynamoDB, Cassandra, etc.). The
  * implementation enables:
  *
  *   - '''Durability''': FSM state survives restarts via event replay
  *   - '''Horizontal scaling''': Multiple nodes can access the same FSM instance
  *   - '''Conflict detection''': Optimistic locking prevents lost updates
  *
  * ==Design==
  *
  * The trait is parameterized by `Id` (instance identifier), `S` (state), and `E` (event). Implementations may require
  * additional type class constraints (e.g., JsonCodec for JSON serialization).
  *
  * ==Implementation Requirements==
  *
  * '''CRITICAL''': The [[append]] method MUST implement optimistic concurrency control. Without this, concurrent
  * modifications from multiple nodes will cause data corruption.
  *
  * ==Snapshotting Strategy==
  *
  * Event lists can grow unboundedly. Snapshotting avoids replaying the full history on startup. The runtime calls
  * [[loadSnapshot]] on startup and only replays events after the snapshot's sequence number via [[loadEventsFrom]].
  *
  * '''Snapshotting is the caller's responsibility.''' The runtime exposes `saveSnapshot` but does NOT call it
  * automatically. You control when to snapshot based on your needs:
  *
  * {{{
  * // Example: Snapshot every 100 events
  * for
  *   _     <- fsm.send(event)
  *   seqNr <- fsm.lastSequenceNr
  *   _     <- ZIO.when(seqNr % 100 == 0)(fsm.saveSnapshot)
  * yield ()
  *
  * // Example: Periodic snapshots with ZIO Schedule
  * fsm.saveSnapshot
  *   .repeat(Schedule.fixed(5.minutes))
  *   .forkDaemon
  * }}}
  *
  * '''Optional cleanup''': After snapshotting, you may delete old events to reclaim storage using [[deleteEventsTo]].
  * This is optional. Keeping events allows a full audit trail. [[deleteEventsTo]] does not remove the instance: the
  * snapshot remains and a new runtime for that id restores the machine. [[deleteInstance]] removes the log and the
  * snapshot. Callers that want the id gone from aliases, indexes, timeouts, and the lock use `fsm.delete` or
  * [[mechanoid.runtime.FSMRuntime.delete]].
  *
  * ==Schema Evolution==
  *
  * When your FSM definition changes over time (adding/removing states or events, changing transitions), be aware of
  * implications for event replay:
  *
  * '''Safe changes:'''
  *   - Adding new states (old events won't transition to them)
  *   - Adding new events (old events unaffected)
  *   - Adding new transitions (old events won't trigger them)
  *
  * '''Potentially unsafe changes:'''
  *   - Removing states: Old events may reference them during replay
  *   - Removing events: Old events of that type exist in the log
  *   - Changing transition targets: Replay may produce different state than original
  *
  * '''Current behavior:''' During replay, events that don't match the current FSM definition cause an
  * [[EventReplayError]]. You must handle this explicitly.
  *
  * ==ZLayer Integration==
  *
  * For efficient resource management, create your EventStore as a ZLayer:
  *
  * {{{
  * // Use the layer helper with your types
  * val storeLayer = PostgresEventStore.layer[MyState, MyEvent]
  *
  * // Usage
  * val program = ZIO.scoped {
  *   for
  *     fsm <- FSMRuntime(orderId, definition, Pending)
  *     _   <- fsm.send(Pay)
  *   yield ()
  * }.provide(storeLayer, transactorLayer)
  * }}}
  *
  * ==Handling Conflicts==
  *
  * When a `SequenceConflictError` occurs, the caller should typically:
  * {{{
  * fsm.send(event)
  *   .retry(Schedule.recurs(3) && Schedule.spaced(100.millis))
  *   .catchSome { case _: SequenceConflictError =>
  *     // Reload state and retry, or fail gracefully
  *   }
  * }}}
  *
  * @tparam Id
  *   The FSM instance identifier type (e.g., UUID, String, Long)
  * @tparam S
  *   The state type
  * @tparam E
  *   The event type
  */
trait EventStore[Id, S, E]:

  /** Append an event with optimistic concurrency control.
    *
    * '''CRITICAL REQUIREMENTS''' - implementations MUST:
    *
    *   1. '''Atomically check''' that the current highest sequence number for this instance equals `expectedSeqNr`. If
    *      not, fail with [[SequenceConflictError]].
    *   2. '''Atomically assign''' a new sequence number (expectedSeqNr + 1) and persist the event. These must be atomic
    *      to prevent race conditions.
    *   3. '''Return''' the newly assigned sequence number on success.
    *
    * '''Why This Matters''':
    *
    * Without optimistic locking, two nodes could simultaneously read seqNr=5, both try to write seqNr=6, and one write
    * would be lost. The `expectedSeqNr` parameter ensures only one succeeds.
    *
    * '''Failure Semantics''':
    *
    *   - On conflict: Fail with `SequenceConflictError(instanceId, expectedSeqNr, actualSeqNr)`
    *   - On DB error: Fail with the underlying exception
    *   - On success: Return the new sequence number
    *
    * @param instanceId
    *   The FSM instance identifier
    * @param event
    *   The event to persist
    * @param expectedSeqNr
    *   The sequence number we expect to be current (0 for new instances)
    * @return
    *   The newly assigned sequence number, or fails with SequenceConflictError
    */
  def append(
      instanceId: Id,
      event: E,
      expectedSeqNr: Long,
  ): ZIO[Any, MechanoidError, Long]

  /** Load all events for an FSM instance in sequence order.
    *
    * Events should be returned in ascending sequence number order.
    *
    * @param instanceId
    *   The FSM instance identifier
    * @return
    *   Stream of stored events
    */
  def loadEvents(instanceId: Id): ZStream[Any, MechanoidError, StoredEvent[Id, E]]

  /** Load events starting from a specific sequence number.
    *
    * Used for incremental loading after a snapshot.
    *
    * @param instanceId
    *   The FSM instance identifier
    * @param fromSequenceNr
    *   Load events with sequence number > this value
    * @return
    *   Stream of stored events after the given sequence
    */
  def loadEventsFrom(
      instanceId: Id,
      fromSequenceNr: Long,
  ): ZStream[Any, MechanoidError, StoredEvent[Id, E]] =
    loadEvents(instanceId).filter(_.sequenceNr > fromSequenceNr)

  /** Get the latest snapshot for an FSM instance.
    *
    * Snapshots are optional optimization. Return None if snapshots are not supported or no snapshot exists.
    *
    * @param instanceId
    *   The FSM instance identifier
    * @return
    *   The latest snapshot if available
    */
  def loadSnapshot(instanceId: Id): ZIO[Any, MechanoidError, Option[FSMSnapshot[Id, S]]]

  /** Save a state snapshot.
    *
    * Snapshots allow faster recovery by avoiding full event replay. Implementations may choose to keep only the latest
    * snapshot or maintain a history.
    *
    * @param snapshot
    *   The snapshot to persist
    */
  def saveSnapshot(snapshot: FSMSnapshot[Id, S]): ZIO[Any, MechanoidError, Unit]

  /** Delete events up to a sequence number (optional log truncation).
    *
    * Used after taking a snapshot to reclaim storage. The snapshot and the instance remain. Default implementation does
    * nothing. To remove the instance, use [[deleteInstance]] from `fsm.delete`.
    *
    * @param instanceId
    *   The FSM instance identifier
    * @param toSequenceNr
    *   Delete events with sequence number <= this value
    */
  def deleteEventsTo(@unused instanceId: Id, @unused toSequenceNr: Long): ZIO[Any, MechanoidError, Unit] =
    ZIO.unit

  /** Remove this instance's event log and snapshot.
    *
    * After success, [[loadEvents]] is empty, [[loadSnapshot]] and [[currentState]] are empty, and [[highestSequenceNr]]
    * is 0. The next [[append]] with `expectedSeqNr` 0 starts a new history. An id with neither events nor a snapshot
    * still succeeds.
    *
    * Does not remove aliases, index rows, timeouts, or the lock. `fsm.delete` and
    * [[mechanoid.runtime.FSMRuntime.delete]] do. No default: a no-op would report the instance gone while
    * [[currentState]] still returns it.
    */
  def deleteInstance(instanceId: Id): ZIO[Any, MechanoidError, Unit]

  /** Get the highest sequence number for an FSM instance.
    *
    * Returns 0 if no events exist.
    *
    * @param instanceId
    *   The FSM instance identifier
    * @return
    *   The highest sequence number
    */
  def highestSequenceNr(instanceId: Id): ZIO[Any, MechanoidError, Long]

  /** Get the current state without loading the full FSM runtime.
    *
    * Useful for quick lookups and queries (e.g., REST API endpoints). Default implementation reads from the latest
    * snapshot.
    *
    * For PostgreSQL, you might override this to query a dedicated current_state table or the snapshots table directly
    * for performance.
    *
    * @param instanceId
    *   The FSM instance identifier
    * @return
    *   The current state if the instance exists, None otherwise
    */
  def currentState(instanceId: Id): ZIO[Any, MechanoidError, Option[S]] =
    loadSnapshot(instanceId).map(_.map(_.state))
end EventStore
