package mechanoid.web

import org.scalajs.dom.{IDBDatabase, IDBTransactionMode}
import zio.*
import mechanoid.core.*
import mechanoid.persistence.lock.*
import java.time.Instant
import scala.scalajs.js

/** IndexedDB-backed [[FSMInstanceLock]]. */
final class IndexedDbInstanceLock private (
    db: IDBDatabase
) extends FSMInstanceLock[String]:

  import IndexedDbInstanceLock.*

  override def tryAcquire(
      instanceId: String,
      nodeId: String,
      duration: Duration,
      now: Instant,
  ): ZIO[Any, MechanoidError, LockResult[String]] =
    get(instanceId, now).flatMap {
      case Some(existing) if existing.isValid(now) && existing.nodeId != nodeId =>
        ZIO.succeed(LockResult.Busy(existing.nodeId, existing.expiresAt))
      case _ =>
        val token = LockToken(
          instanceId = instanceId,
          nodeId = nodeId,
          acquiredAt = now,
          expiresAt = now.plusMillis(duration.toMillis),
        )
        put(token).as(LockResult.Acquired(token))
    }

  override def acquire(
      instanceId: String,
      nodeId: String,
      duration: Duration,
      timeout: Duration,
  ): ZIO[Any, MechanoidError, LockResult[String]] =
    for
      start <- Clock.instant
      deadline = start.plusMillis(timeout.toMillis)
      result <- attempt(instanceId, nodeId, duration, deadline)
    yield result

  private def attempt(
      instanceId: String,
      nodeId: String,
      duration: Duration,
      deadline: Instant,
  ): ZIO[Any, MechanoidError, LockResult[String]] =
    for
      now    <- Clock.instant
      result <- tryAcquire(instanceId, nodeId, duration, now)
      out    <- result match
        case a @ LockResult.Acquired(_) =>
          ZIO.succeed(a)
        case LockResult.Busy(_, _) if now.isAfter(deadline) =>
          ZIO.succeed(LockResult.TimedOut())
        case LockResult.Busy(_, _) =>
          ZIO.sleep(50.millis) *> attempt(instanceId, nodeId, duration, deadline)
        case other =>
          ZIO.succeed(other)
    yield out

  override def release(token: LockToken[String]): ZIO[Any, MechanoidError, Boolean] =
    get(token.instanceId, Instant.EPOCH).flatMap {
      case Some(existing) if existing.nodeId == token.nodeId =>
        Idb
          .txn(db, Seq(Idb.LocksStore), IDBTransactionMode.readwrite) { tx =>
            Idb.request(Idb.store(tx, Idb.LocksStore).delete(token.instanceId))
          }
          .as(true)
      case _ =>
        ZIO.succeed(false)
    }

  override def extend(
      token: LockToken[String],
      additionalDuration: Duration,
      now: Instant,
  ): ZIO[Any, MechanoidError, Option[LockToken[String]]] =
    get(token.instanceId, now).flatMap {
      case Some(existing) if existing.nodeId == token.nodeId && existing.isValid(now) =>
        val updated = existing.copy(expiresAt = now.plusMillis(additionalDuration.toMillis))
        put(updated).as(Some(updated))
      case _ =>
        ZIO.succeed(None)
    }

  override def get(instanceId: String, now: Instant): ZIO[Any, MechanoidError, Option[LockToken[String]]] =
    Idb
      .txn(db, Seq(Idb.LocksStore), IDBTransactionMode.readonly) { tx =>
        Idb.request(Idb.store(tx, Idb.LocksStore).get(instanceId))
      }
      .map { raw =>
        if raw == null || js.isUndefined(raw.asInstanceOf[js.Any]) then None
        else Some(LockRow.fromJs(raw.asInstanceOf[js.Dynamic]).toToken)
      }

  private def put(token: LockToken[String]): ZIO[Any, MechanoidError, Unit] =
    Idb
      .txn(db, Seq(Idb.LocksStore), IDBTransactionMode.readwrite) { tx =>
        Idb.request(Idb.store(tx, Idb.LocksStore).put(LockRow.fromToken(token).toJs))
      }
      .unit
end IndexedDbInstanceLock

object IndexedDbInstanceLock:

  final case class LockRow(
      instanceId: String,
      nodeId: String,
      acquiredAtEpoch: Long,
      expiresAtEpoch: Long,
  ):
    def toJs: js.Dynamic =
      js.Dynamic.literal(
        instanceId = instanceId,
        nodeId = nodeId,
        acquiredAtEpoch = acquiredAtEpoch.toDouble,
        expiresAtEpoch = expiresAtEpoch.toDouble,
      )

    def toToken: LockToken[String] =
      LockToken(
        instanceId = instanceId,
        nodeId = nodeId,
        acquiredAt = Instant.ofEpochMilli(acquiredAtEpoch),
        expiresAt = Instant.ofEpochMilli(expiresAtEpoch),
      )
  end LockRow

  object LockRow:
    def fromToken(t: LockToken[String]): LockRow =
      LockRow(
        instanceId = t.instanceId,
        nodeId = t.nodeId,
        acquiredAtEpoch = t.acquiredAt.toEpochMilli,
        expiresAtEpoch = t.expiresAt.toEpochMilli,
      )

    def fromJs(raw: js.Dynamic): LockRow =
      LockRow(
        instanceId = raw.instanceId.asInstanceOf[String],
        nodeId = raw.nodeId.asInstanceOf[String],
        acquiredAtEpoch = raw.acquiredAtEpoch.asInstanceOf[Double].toLong,
        expiresAtEpoch = raw.expiresAtEpoch.asInstanceOf[Double].toLong,
      )
  end LockRow

  def make(dbName: String = "mechanoid"): ZIO[Any, MechanoidError, IndexedDbInstanceLock] =
    Idb.open(dbName).map(new IndexedDbInstanceLock(_))

  def layer(dbName: String = "mechanoid"): ZLayer[Any, MechanoidError, FSMInstanceLock[String]] =
    ZLayer.fromZIO(make(dbName))
end IndexedDbInstanceLock
