package mechanoid.web

import org.scalajs.dom.{IDBDatabase, IDBTransactionMode}
import zio.*
import zio.json.*
import zio.stream.*
import mechanoid.core.*
import mechanoid.persistence.*
import java.time.Instant
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

/** IndexedDB-backed [[EventStore]] with optimistic concurrency on sequence numbers. */
final class IndexedDbEventStore[S: JsonCodec, E: JsonCodec] private (
    db: IDBDatabase,
    notify: String => UIO[Unit],
) extends EventStore[String, S, E]:

  import IndexedDbEventStore.*

  override def append(
      instanceId: String,
      event: E,
      expectedSeqNr: Long,
  ): ZIO[Any, MechanoidError, Long] =
    val newSeqNr = expectedSeqNr + 1
    for
      now     <- Clock.instant
      highest <- highestSequenceNr(instanceId)
      _       <- ZIO.when(highest != expectedSeqNr) {
        ZIO.fail(SequenceConflictError(instanceId, expectedSeqNr, highest))
      }
      row = EventRow(
        key = s"$instanceId:$newSeqNr",
        instanceId = instanceId,
        sequenceNr = newSeqNr,
        eventJson = event.toJson,
        timestampEpoch = now.toEpochMilli,
      )
      _ <- Idb.txn(db, Seq(Idb.EventsStore), IDBTransactionMode.readwrite) { tx =>
        Idb.request(Idb.store(tx, Idb.EventsStore).put(row.toJs))
      }
      _ <- notify(instanceId)
    yield newSeqNr
    end for
  end append

  override def loadEvents(instanceId: String): ZStream[Any, MechanoidError, StoredEvent[String, E]] =
    ZStream.unwrap {
      loadEventRows(instanceId).map { rows =>
        ZStream.fromIterable(rows.flatMap(decodeEvent))
      }
    }

  override def loadEventsFrom(
      instanceId: String,
      fromSequenceNr: Long,
  ): ZStream[Any, MechanoidError, StoredEvent[String, E]] =
    loadEvents(instanceId).filter(_.sequenceNr > fromSequenceNr)

  override def loadSnapshot(instanceId: String): ZIO[Any, MechanoidError, Option[FSMSnapshot[String, S]]] =
    Idb
      .txn(db, Seq(Idb.SnapshotsStore), IDBTransactionMode.readonly) { tx =>
        Idb.request(Idb.store(tx, Idb.SnapshotsStore).get(instanceId))
      }
      .map { result =>
        if result == null || js.isUndefined(result.asInstanceOf[js.Any]) then None
        else
          val row = SnapshotRow.fromJs(result.asInstanceOf[js.Dynamic])
          row.stateJson.fromJson[S].toOption.map { state =>
            FSMSnapshot(
              instanceId = row.instanceId,
              state = state,
              sequenceNr = row.sequenceNr,
              timestamp = Instant.ofEpochMilli(row.timestampEpoch),
            )
          }
      }

  override def saveSnapshot(snapshot: FSMSnapshot[String, S]): ZIO[Any, MechanoidError, Unit] =
    val row = SnapshotRow(
      instanceId = snapshot.instanceId,
      stateJson = snapshot.state.toJson,
      sequenceNr = snapshot.sequenceNr,
      timestampEpoch = snapshot.timestamp.toEpochMilli,
    )
    Idb
      .txn(db, Seq(Idb.SnapshotsStore), IDBTransactionMode.readwrite) { tx =>
        Idb.request(Idb.store(tx, Idb.SnapshotsStore).put(row.toJs))
      }
      .unit <* notify(snapshot.instanceId)
  end saveSnapshot

  override def deleteEventsTo(instanceId: String, toSequenceNr: Long): ZIO[Any, MechanoidError, Unit] =
    for
      rows <- loadEventRows(instanceId)
      toDelete = rows.filter(_.sequenceNr <= toSequenceNr)
      _ <- ZIO.when(toDelete.nonEmpty) {
        // Issue every delete synchronously on one transaction. Awaiting between deletes
        // (ZIO.foreach + Idb.request) lets IndexedDB auto-commit and drops the rest.
        ZIO.async[Any, MechanoidError, Unit] { cb =>
          val tx    = db.transaction(js.Array(Idb.EventsStore), IDBTransactionMode.readwrite)
          val store = tx.objectStore(Idb.EventsStore)
          toDelete.foreach(r => store.delete(r.key))
          tx.oncomplete = (_: org.scalajs.dom.Event) => cb(ZIO.unit)
          tx.onerror = (_: org.scalajs.dom.Event) =>
            cb(ZIO.fail(PersistenceError(s"IndexedDB deleteEventsTo failed: ${tx.error}")))
          tx.onabort = (_: org.scalajs.dom.Event) =>
            cb(ZIO.fail(PersistenceError(s"IndexedDB deleteEventsTo aborted: ${tx.error}")))
        }
      }
    yield ()

  override def highestSequenceNr(instanceId: String): ZIO[Any, MechanoidError, Long] =
    loadEventRows(instanceId).map(_.map(_.sequenceNr).maxOption.getOrElse(0L))

  private def loadEventRows(instanceId: String): ZIO[Any, MechanoidError, List[EventRow]] =
    Idb.txn(db, Seq(Idb.EventsStore), IDBTransactionMode.readonly) { tx =>
      val store = Idb.store(tx, Idb.EventsStore)
      val index = store.index("byInstance")
      Idb.request(index.getAll(instanceId)).map { result =>
        val arr = result.asInstanceOf[js.Array[js.Dynamic]]
        arr.toList.map(EventRow.fromJs).sortBy(_.sequenceNr)
      }
    }

  private def decodeEvent(row: EventRow): Option[StoredEvent[String, E]] =
    row.eventJson.fromJson[E].toOption.map { event =>
      StoredEvent(
        instanceId = row.instanceId,
        sequenceNr = row.sequenceNr,
        event = event,
        timestamp = Instant.ofEpochMilli(row.timestampEpoch),
      )
    }
end IndexedDbEventStore

object IndexedDbEventStore:

  final case class EventRow(
      key: String,
      instanceId: String,
      sequenceNr: Long,
      eventJson: String,
      timestampEpoch: Long,
  ):
    def toJs: js.Dynamic =
      js.Dynamic.literal(
        key = key,
        instanceId = instanceId,
        sequenceNr = sequenceNr.toDouble,
        eventJson = eventJson,
        timestampEpoch = timestampEpoch.toDouble,
      )
  end EventRow

  object EventRow:
    def fromJs(raw: js.Dynamic): EventRow =
      EventRow(
        key = raw.key.asInstanceOf[String],
        instanceId = raw.instanceId.asInstanceOf[String],
        sequenceNr = raw.sequenceNr.asInstanceOf[Double].toLong,
        eventJson = raw.eventJson.asInstanceOf[String],
        timestampEpoch = raw.timestampEpoch.asInstanceOf[Double].toLong,
      )

  final case class SnapshotRow(
      instanceId: String,
      stateJson: String,
      sequenceNr: Long,
      timestampEpoch: Long,
  ):
    def toJs: js.Dynamic =
      js.Dynamic.literal(
        instanceId = instanceId,
        stateJson = stateJson,
        sequenceNr = sequenceNr.toDouble,
        timestampEpoch = timestampEpoch.toDouble,
      )
  end SnapshotRow

  object SnapshotRow:
    def fromJs(raw: js.Dynamic): SnapshotRow =
      SnapshotRow(
        instanceId = raw.instanceId.asInstanceOf[String],
        stateJson = raw.stateJson.asInstanceOf[String],
        sequenceNr = raw.sequenceNr.asInstanceOf[Double].toLong,
        timestampEpoch = raw.timestampEpoch.asInstanceOf[Double].toLong,
      )

  def make[S: JsonCodec, E: JsonCodec](
      dbName: String = "mechanoid",
      notify: String => UIO[Unit] = _ => ZIO.unit,
  ): ZIO[Any, MechanoidError, IndexedDbEventStore[S, E]] =
    Idb.open(dbName).map(new IndexedDbEventStore(_, notify))

  def layer[S: JsonCodec: Tag, E: JsonCodec: Tag](
      dbName: String = "mechanoid"
  ): ZLayer[Any, MechanoidError, EventStore[String, S, E]] =
    ZLayer.fromZIO(make[S, E](dbName))
end IndexedDbEventStore
