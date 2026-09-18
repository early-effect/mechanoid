package mechanoid.web

import org.scalajs.dom.{IDBDatabase, IDBTransactionMode, IDBValue}
import zio.*
import mechanoid.core.*
import mechanoid.persistence.timeout.*
import java.time.Instant
import scala.scalajs.js

/** IndexedDB-backed [[TimeoutStore]]. Rows are keyed by `(instanceId, timeoutKey)`. */
final class IndexedDbTimeoutStore private (
    db: IDBDatabase,
    notify: String => UIO[Unit],
) extends TimeoutStore[String]:

  import IndexedDbTimeoutStore.*

  override def schedule(
      instanceId: String,
      name: String,
      stateHash: Int,
      sequenceNr: Long,
      deadline: Instant,
  ): ZIO[Any, MechanoidError, ScheduledTimeout[String]] =
    for
      now <- Clock.instant
      timeout = ScheduledTimeout(
        instanceId = instanceId,
        name = name,
        stateHash = stateHash,
        sequenceNr = sequenceNr,
        deadline = deadline,
        createdAt = now,
      )
      _ <- put(timeout)
      _ <- notify(instanceId)
    yield timeout

  override def cancel(instanceId: String): ZIO[Any, MechanoidError, Boolean] =
    for
      existing <- get(instanceId)
      _        <- ZIO.when(existing.nonEmpty) {
        ZIO.foreachDiscard(existing)(t => delete(t.instanceId, t.name)) *> notify(instanceId)
      }
    yield existing.nonEmpty

  override def cancel(instanceId: String, name: String): ZIO[Any, MechanoidError, Boolean] =
    for
      existing <- get(instanceId, name)
      _        <- ZIO.when(existing.isDefined) {
        delete(instanceId, name) *> notify(instanceId)
      }
    yield existing.isDefined

  override def queryExpired(limit: Int, now: Instant): ZIO[Any, MechanoidError, List[ScheduledTimeout[String]]] =
    all().map(_.filter(_.canBeClaimed(now)).sortBy(_.deadline).take(limit))

  override def claim(
      instanceId: String,
      name: String,
      nodeId: String,
      claimDuration: Duration,
      now: Instant,
  ): ZIO[Any, MechanoidError, ClaimResult] =
    get(instanceId, name).flatMap {
      case None =>
        ZIO.succeed(ClaimResult.NotFound)
      case Some(t) if t.isClaimed(now) =>
        ZIO.succeed(ClaimResult.AlreadyClaimed(t.claimedBy.get, t.claimedUntil.get))
      case Some(t) =>
        val claimed = t.copy(
          claimedBy = Some(nodeId),
          claimedUntil = Some(now.plusMillis(claimDuration.toMillis)),
        )
        put(claimed).as(ClaimResult.Claimed(claimed))
    }

  override def complete(instanceId: String, name: String, sequenceNr: Long): ZIO[Any, MechanoidError, Boolean] =
    get(instanceId, name).flatMap {
      case Some(t) if t.sequenceNr == sequenceNr =>
        cancel(instanceId, name)
      case _ =>
        ZIO.succeed(false)
    }

  override def release(instanceId: String, name: String): ZIO[Any, MechanoidError, Boolean] =
    get(instanceId, name).flatMap {
      case Some(t) =>
        put(t.copy(claimedBy = None, claimedUntil = None)).as(true)
      case None =>
        ZIO.succeed(false)
    }

  override def get(instanceId: String): ZIO[Any, MechanoidError, Chunk[ScheduledTimeout[String]]] =
    all().map(rows => Chunk.fromIterable(rows.filter(_.instanceId == instanceId)))

  override def get(instanceId: String, name: String): ZIO[Any, MechanoidError, Option[ScheduledTimeout[String]]] =
    Idb
      .txn(db, Seq(Idb.TimeoutsStore), IDBTransactionMode.readonly) { tx =>
        Idb.request(Idb.store(tx, Idb.TimeoutsStore).get(compositeKey(instanceId, name)))
      }
      .map(readOptional)

  private def put(timeout: ScheduledTimeout[String]): ZIO[Any, MechanoidError, Unit] =
    Idb
      .txn(db, Seq(Idb.TimeoutsStore), IDBTransactionMode.readwrite) { tx =>
        Idb.request(Idb.store(tx, Idb.TimeoutsStore).put(TimeoutRecord.fromScheduled(timeout)))
      }
      .unit

  private def delete(instanceId: String, name: String): ZIO[Any, MechanoidError, Unit] =
    Idb
      .txn(db, Seq(Idb.TimeoutsStore), IDBTransactionMode.readwrite) { tx =>
        Idb.request(Idb.store(tx, Idb.TimeoutsStore).delete(compositeKey(instanceId, name)))
      }
      .unit

  private def all(): ZIO[Any, MechanoidError, List[ScheduledTimeout[String]]] =
    Idb
      .txn(db, Seq(Idb.TimeoutsStore), IDBTransactionMode.readonly) { tx =>
        Idb.request(Idb.store(tx, Idb.TimeoutsStore).getAll()).map { result =>
          result.asInstanceOf[js.Array[TimeoutRecord]].toList.flatMap(TimeoutRecord.toScheduled)
        }
      }

  private def readOptional(raw: IDBValue): Option[ScheduledTimeout[String]] =
    if raw == null || js.isUndefined(raw) then None
    else TimeoutRecord.toScheduled(raw.asInstanceOf[TimeoutRecord])

  private def compositeKey(instanceId: String, name: String): js.Array[String] =
    js.Array(instanceId, name)
end IndexedDbTimeoutStore

object IndexedDbTimeoutStore:

  /** IndexedDB row for a named timeout. Constructed as a `js.Object` facade, not `js.Dynamic`. */
  trait TimeoutRecord extends js.Object:
    val instanceId: String
    val timeoutKey: String
    val stateHash: Double
    val sequenceNr: Double
    val deadlineEpoch: Double
    val createdAtEpoch: Double
    val claimedBy: js.UndefOr[String]
    val claimedUntilEpoch: js.UndefOr[Double]

  object TimeoutRecord:
    def fromScheduled(t: ScheduledTimeout[String]): TimeoutRecord =
      new TimeoutRecord:
        val instanceId: String                    = t.instanceId
        val timeoutKey: String                    = t.name
        val stateHash: Double                     = t.stateHash.toDouble
        val sequenceNr: Double                    = t.sequenceNr.toDouble
        val deadlineEpoch: Double                 = t.deadline.toEpochMilli.toDouble
        val createdAtEpoch: Double                = t.createdAt.toEpochMilli.toDouble
        val claimedBy: js.UndefOr[String]         = t.claimedBy.fold[js.UndefOr[String]](js.undefined)(identity)
        val claimedUntilEpoch: js.UndefOr[Double] =
          t.claimedUntil.fold[js.UndefOr[Double]](js.undefined)(_.toEpochMilli.toDouble)

    def toScheduled(row: TimeoutRecord): Option[ScheduledTimeout[String]] =
      if row == null || js.isUndefined(row) then None
      else
        Some(
          ScheduledTimeout(
            instanceId = row.instanceId,
            name = row.timeoutKey,
            stateHash = row.stateHash.toInt,
            sequenceNr = row.sequenceNr.toLong,
            deadline = Instant.ofEpochMilli(row.deadlineEpoch.toLong),
            createdAt = Instant.ofEpochMilli(row.createdAtEpoch.toLong),
            claimedBy = row.claimedBy.toOption,
            claimedUntil = row.claimedUntilEpoch.toOption.map(ms => Instant.ofEpochMilli(ms.toLong)),
          )
        )
  end TimeoutRecord

  def make(
      dbName: String = "mechanoid",
      notify: String => UIO[Unit] = _ => ZIO.unit,
  ): ZIO[Any, MechanoidError, IndexedDbTimeoutStore] =
    Idb.open(dbName).map(new IndexedDbTimeoutStore(_, notify))

  def layer(dbName: String = "mechanoid"): ZLayer[Any, MechanoidError, TimeoutStore[String]] =
    ZLayer.fromZIO(make(dbName))
end IndexedDbTimeoutStore
