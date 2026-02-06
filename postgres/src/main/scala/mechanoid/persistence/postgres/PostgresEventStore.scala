package mechanoid.persistence.postgres

import saferis.*
import saferis.postgres.given
import zio.*
import zio.json.*
import zio.stream.*
import mechanoid.core.*
import mechanoid.persistence.*

/** PostgreSQL implementation of EventStore using Saferis.
  *
  * This implementation uses optimistic locking via sequence number checks to ensure exactly-once event persistence in
  * distributed environments.
  *
  * ==Usage==
  * {{{
  * // Define your types with Finite (JsonCodec is auto-derived)
  * enum MyState derives Finite:
  *   case Idle, Running, Done
  *
  * enum MyEvent derives Finite:
  *   case Started(id: String)
  *   case Completed
  *
  * // Create the store layer using the helper
  * val storeLayer = PostgresEventStore.layer[MyState, MyEvent]
  *
  * // Use with FSMRuntime
  * FSMRuntime(id, definition, initialState).provide(storeLayer, transactorLayer)
  * }}}
  */
class PostgresEventStore[S: JsonCodec, E: JsonCodec](transactor: Transactor) extends EventStore[String, S, E]:

  private val eventsTable = Table[EventRow[E]]

  override def append(
      instanceId: String,
      event: E,
      expectedSeqNr: Long,
  ): ZIO[Any, MechanoidError, Long] =
    // New sequence number is expectedSeqNr + 1
    // expectedSeqNr is the expected CURRENT highest sequence number
    val newSeqNr = expectedSeqNr + 1

    // First check current sequence outside transaction
    val checkAndInsert = for
      currentSeq <- transactor
        .run {
          Query[EventRow[E]]
            .where(_.instanceId)
            .eq(instanceId)
            .selectAggregate(_.sequenceNr)(_.max.coalesce(0L))
            .queryValue[Long]
        }
        .map(_.getOrElse(0L))

      _ <- ZIO.when(currentSeq != expectedSeqNr) {
        ZIO.fail(SequenceConflictError(instanceId, expectedSeqNr, currentSeq))
      }

      now <- Clock.instant
      _   <- transactor.run {
        Insert[EventRow[E]]
          .value(_.instanceId, instanceId)
          .value(_.sequenceNr, newSeqNr)
          .value(_.eventData, Json(event))
          .value(_.createdAt, now)
          .build
          .dml
      }
    yield newSeqNr

    checkAndInsert
      .catchSome {
        // Handle unique constraint violation from concurrent inserts
        case _: SaferisError.ConstraintViolation =>
          ZIO.fail(SequenceConflictError(instanceId, expectedSeqNr, expectedSeqNr))
      }
      .mapError {
        case e: MechanoidError => e
        case e                 => PersistenceError.fromError(e)
      }
  end append

  override def loadEvents(instanceId: String): ZStream[Any, MechanoidError, StoredEvent[String, E]] =
    ZStream.fromIterableZIO {
      transactor
        .run {
          Query[EventRow[E]]
            .where(_.instanceId)
            .eq(instanceId)
            .orderBy(eventsTable.sequenceNr.asc)
            .query[EventRow[E]]
        }
        .map(_.map(rowToStoredEvent))
        .mapError(PersistenceError.fromError)
    }

  override def loadEventsFrom(
      instanceId: String,
      fromSequenceNr: Long,
  ): ZStream[Any, MechanoidError, StoredEvent[String, E]] =
    ZStream.fromIterableZIO {
      transactor
        .run {
          Query[EventRow[E]]
            .where(_.instanceId)
            .eq(instanceId)
            .where(_.sequenceNr)
            .gt(fromSequenceNr)
            .orderBy(eventsTable.sequenceNr.asc)
            .query[EventRow[E]]
        }
        .map(_.map(rowToStoredEvent))
        .mapError(PersistenceError.fromError)
    }

  override def loadSnapshot(instanceId: String): ZIO[Any, MechanoidError, Option[FSMSnapshot[String, S]]] =
    transactor
      .run {
        Query[SnapshotRow[S]]
          .where(_.instanceId)
          .eq(instanceId)
          .queryOne[SnapshotRow[S]]
      }
      .map(_.map(rowToSnapshot))
      .mapError(PersistenceError.fromError)

  override def saveSnapshot(snapshot: FSMSnapshot[String, S]): ZIO[Any, MechanoidError, Unit] =
    val row = SnapshotRow[S](snapshot.instanceId, Json(snapshot.state), snapshot.sequenceNr, snapshot.timestamp)

    transactor
      .run {
        Upsert[SnapshotRow[S]]
          .values(row)
          .onConflict(_.instanceId)
          .doUpdateAll
          .build
          .dml
      }
      .unit
      .mapError(PersistenceError.fromError)
  end saveSnapshot

  override def deleteEventsTo(instanceId: String, toSequenceNr: Long): ZIO[Any, MechanoidError, Unit] =
    transactor
      .run {
        Delete[EventRow[E]]
          .where(_.instanceId)
          .eq(instanceId)
          .where(_.sequenceNr)
          .lte(toSequenceNr)
          .build
          .dml
      }
      .unit
      .mapError(PersistenceError.fromError)

  override def highestSequenceNr(instanceId: String): ZIO[Any, MechanoidError, Long] =
    transactor
      .run {
        Query[EventRow[E]]
          .where(_.instanceId)
          .eq(instanceId)
          .selectAggregate(_.sequenceNr)(_.max.coalesce(0L))
          .queryValue[Long]
      }
      .map(_.getOrElse(0L))
      .mapError(PersistenceError.fromError)

  private def rowToStoredEvent(row: EventRow[E]): StoredEvent[String, E] =
    StoredEvent(
      instanceId = row.instanceId,
      sequenceNr = row.sequenceNr,
      event = row.eventData.value,
      timestamp = row.createdAt,
    )

  private def rowToSnapshot(row: SnapshotRow[S]): FSMSnapshot[String, S] =
    FSMSnapshot(
      instanceId = row.instanceId,
      state = row.stateData.value,
      sequenceNr = row.sequenceNr,
      timestamp = row.createdAt,
    )
end PostgresEventStore

object PostgresEventStore:

  /** Create a ZLayer for PostgresEventStore with the given state and event types.
    *
    * JsonCodec is automatically derived from Finite, so users only need `derives Finite` on their state and event
    * types.
    *
    * Usage:
    * {{{
    * import mechanoid.*
    * import mechanoid.postgres.*
    *
    * enum MyState derives Finite:
    *   case Idle, Running, Done
    *
    * enum MyEvent derives Finite:
    *   case Started, Completed
    *
    * val storeLayer = PostgresEventStore.makeLayer[MyState, MyEvent]
    * }}}
    */
  @scala.annotation.nowarn("msg=unused implicit parameter")
  transparent inline def makeLayer[S: Tag, E: Tag](using
      inline fs: mechanoid.core.Finite[S],
      inline fe: mechanoid.core.Finite[E],
      inline ms: scala.deriving.Mirror.Of[S],
      inline me: scala.deriving.Mirror.Of[E],
  ): ZLayer[Transactor, Nothing, EventStore[String, S, E]] =
    given JsonCodec[S] = JsonCodec.derived[S]
    given JsonCodec[E] = JsonCodec.derived[E]
    ZLayer.fromFunction((xa: Transactor) => new PostgresEventStore[S, E](xa))
  end makeLayer

end PostgresEventStore
