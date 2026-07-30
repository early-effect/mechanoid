package mechanoid.web

import org.scalajs.dom.{IDBDatabase, IDBTransactionMode}
import zio.*
import mechanoid.core.*
import mechanoid.persistence.timeout.*
import java.time.Instant
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

/** IndexedDB-backed [[TimeoutStore]]. */
final class IndexedDbTimeoutStore private (
    db: IDBDatabase,
    notify: String => UIO[Unit],
) extends TimeoutStore[String]:

  import IndexedDbTimeoutStore.*

  override def schedule(
      instanceId: String,
      stateHash: Int,
      sequenceNr: Long,
      deadline: Instant,
  ): ZIO[Any, MechanoidError, ScheduledTimeout[String]] =
    for
      now <- Clock.instant
      timeout = ScheduledTimeout(
        instanceId = instanceId,
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
      _        <- ZIO.when(existing.isDefined) {
        Idb.txn(db, Seq(Idb.TimeoutsStore), IDBTransactionMode.readwrite) { tx =>
          Idb.request(Idb.store(tx, Idb.TimeoutsStore).delete(instanceId))
        } *> notify(instanceId)
      }
    yield existing.isDefined

  override def queryExpired(limit: Int, now: Instant): ZIO[Any, MechanoidError, List[ScheduledTimeout[String]]] =
    all().map(_.filter(_.canBeClaimed(now)).sortBy(_.deadline).take(limit))

  override def claim(
      instanceId: String,
      nodeId: String,
      claimDuration: Duration,
      now: Instant,
  ): ZIO[Any, MechanoidError, ClaimResult] =
    get(instanceId).flatMap {
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

  override def complete(instanceId: String, sequenceNr: Long): ZIO[Any, MechanoidError, Boolean] =
    get(instanceId).flatMap {
      case Some(t) if t.sequenceNr == sequenceNr =>
        cancel(instanceId)
      case _ =>
        ZIO.succeed(false)
    }

  override def release(instanceId: String): ZIO[Any, MechanoidError, Boolean] =
    get(instanceId).flatMap {
      case Some(t) =>
        put(t.copy(claimedBy = None, claimedUntil = None)).as(true)
      case None =>
        ZIO.succeed(false)
    }

  override def get(instanceId: String): ZIO[Any, MechanoidError, Option[ScheduledTimeout[String]]] =
    Idb
      .txn(db, Seq(Idb.TimeoutsStore), IDBTransactionMode.readonly) { tx =>
        Idb.request(Idb.store(tx, Idb.TimeoutsStore).get(instanceId))
      }
      .map { raw =>
        if raw == null || js.isUndefined(raw.asInstanceOf[js.Any]) then None
        else Some(TimeoutRow.fromJs(raw.asInstanceOf[js.Dynamic]).toScheduled)
      }

  private def put(timeout: ScheduledTimeout[String]): ZIO[Any, MechanoidError, Unit] =
    Idb
      .txn(db, Seq(Idb.TimeoutsStore), IDBTransactionMode.readwrite) { tx =>
        Idb.request(Idb.store(tx, Idb.TimeoutsStore).put(TimeoutRow.fromScheduled(timeout).toJs))
      }
      .unit

  private def all(): ZIO[Any, MechanoidError, List[ScheduledTimeout[String]]] =
    Idb
      .txn(db, Seq(Idb.TimeoutsStore), IDBTransactionMode.readonly) { tx =>
        Idb.request(Idb.store(tx, Idb.TimeoutsStore).getAll()).map { result =>
          result.asInstanceOf[js.Array[js.Dynamic]].toList.map(TimeoutRow.fromJs(_).toScheduled)
        }
      }
end IndexedDbTimeoutStore

object IndexedDbTimeoutStore:

  final case class TimeoutRow(
      instanceId: String,
      stateHash: Int,
      sequenceNr: Long,
      deadlineEpoch: Long,
      createdAtEpoch: Long,
      claimedBy: js.UndefOr[String],
      claimedUntilEpoch: js.UndefOr[Double],
  ):
    def toJs: js.Dynamic =
      val lit = js.Dynamic.literal(
        instanceId = instanceId,
        stateHash = stateHash,
        sequenceNr = sequenceNr.toDouble,
        deadlineEpoch = deadlineEpoch.toDouble,
        createdAtEpoch = createdAtEpoch.toDouble,
      )
      claimedBy.foreach(v => lit.claimedBy = v)
      claimedUntilEpoch.foreach(v => lit.claimedUntilEpoch = v)
      lit
    end toJs

    def toScheduled: ScheduledTimeout[String] =
      ScheduledTimeout(
        instanceId = instanceId,
        stateHash = stateHash,
        sequenceNr = sequenceNr,
        deadline = Instant.ofEpochMilli(deadlineEpoch),
        createdAt = Instant.ofEpochMilli(createdAtEpoch),
        claimedBy = claimedBy.toOption,
        claimedUntil = claimedUntilEpoch.toOption.map(ms => Instant.ofEpochMilli(ms.toLong)),
      )
  end TimeoutRow

  object TimeoutRow:
    def fromScheduled(t: ScheduledTimeout[String]): TimeoutRow =
      TimeoutRow(
        instanceId = t.instanceId,
        stateHash = t.stateHash,
        sequenceNr = t.sequenceNr,
        deadlineEpoch = t.deadline.toEpochMilli,
        createdAtEpoch = t.createdAt.toEpochMilli,
        claimedBy = t.claimedBy.orUndefined,
        claimedUntilEpoch = t.claimedUntil.map(_.toEpochMilli.toDouble).orUndefined,
      )

    def fromJs(raw: js.Dynamic): TimeoutRow =
      TimeoutRow(
        instanceId = raw.instanceId.asInstanceOf[String],
        stateHash = raw.stateHash.asInstanceOf[Double].toInt,
        sequenceNr = raw.sequenceNr.asInstanceOf[Double].toLong,
        deadlineEpoch = raw.deadlineEpoch.asInstanceOf[Double].toLong,
        createdAtEpoch = raw.createdAtEpoch.asInstanceOf[Double].toLong,
        claimedBy = raw.claimedBy.asInstanceOf[js.UndefOr[String]],
        claimedUntilEpoch = raw.claimedUntilEpoch.asInstanceOf[js.UndefOr[Double]],
      )
  end TimeoutRow

  def make(
      dbName: String = "mechanoid",
      notify: String => UIO[Unit] = _ => ZIO.unit,
  ): ZIO[Any, MechanoidError, IndexedDbTimeoutStore] =
    Idb.open(dbName).map(new IndexedDbTimeoutStore(_, notify))

  def layer(dbName: String = "mechanoid"): ZLayer[Any, MechanoidError, TimeoutStore[String]] =
    ZLayer.fromZIO(make(dbName))
end IndexedDbTimeoutStore
