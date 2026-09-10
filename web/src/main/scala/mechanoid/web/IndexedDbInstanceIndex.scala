package mechanoid.web

import org.scalajs.dom
import org.scalajs.dom.{IDBDatabase, IDBTransactionMode}
import zio.*
import mechanoid.core.*
import mechanoid.persistence.{Alias, InstanceIndex}
import java.time.Instant
import scala.scalajs.js

/** IndexedDB-backed [[InstanceIndex]]. Unique key is `"$namespace:$aliasKey"`. */
final class IndexedDbInstanceIndex private (
    db: IDBDatabase
) extends InstanceIndex[String]:

  import IndexedDbInstanceIndex.*

  override def bindAll(aliases: Chunk[Alias], instanceId: String): ZIO[Any, MechanoidError, Unit] =
    if aliases.isEmpty then ZIO.unit
    else
      Clock.instant.flatMap { now =>
        ZIO.async[Any, MechanoidError, Unit] { cb =>
          val tx      = db.transaction(js.Array(Idb.AliasesStore), IDBTransactionMode.readwrite)
          val store   = tx.objectStore(Idb.AliasesStore)
          val n       = aliases.size
          val found   = new Array[Option[AliasRow]](n)
          var done    = 0
          var settled = false

          def fail(error: MechanoidError): Unit =
            if !settled then
              settled = true
              try tx.abort()
              catch case _: Throwable => ()
              cb(ZIO.fail(error))

          def afterGets(): Unit =
            aliases.zipWithIndex.foreach { (alias, i) =>
              found(i) match
                case Some(row) if row.instanceId != instanceId =>
                  fail(UniqueAliasError(alias.namespace, alias.key, row.instanceId, instanceId))
                case _ => ()
            }
            if !settled then
              aliases.zipWithIndex.foreach { (alias, i) =>
                if found(i).isEmpty then store.put(AliasRow.fromAlias(alias, instanceId, now).toJs)
              }
          end afterGets

          aliases.zipWithIndex.foreach { (alias, i) =>
            val req = store.get(AliasRow.keyOf(alias))
            req.onsuccess = (_: dom.Event) =>
              val result = req.result
              found(i) =
                if result == null || js.isUndefined(result.asInstanceOf[js.Any]) then None
                else Some(AliasRow.fromJs(result.asInstanceOf[js.Dynamic]))
              done += 1
              if done == n then afterGets()
            req.onerror = (_: dom.Event) => fail(PersistenceError(s"IndexedDB alias get failed: ${req.error}"))
          }

          tx.oncomplete = (_: dom.Event) =>
            if !settled then
              settled = true
              cb(ZIO.unit)
          tx.onerror = (_: dom.Event) => fail(PersistenceError(s"IndexedDB bindAll failed: ${tx.error}"))
          tx.onabort =
            (_: dom.Event) => if !settled then fail(PersistenceError(s"IndexedDB bindAll aborted: ${tx.error}"))
        }
      }

  override def resolve(alias: Alias): ZIO[Any, MechanoidError, Option[String]] =
    Idb.txn(db, Seq(Idb.AliasesStore), IDBTransactionMode.readonly) { tx =>
      Idb.request(Idb.store(tx, Idb.AliasesStore).get(AliasRow.keyOf(alias))).map { result =>
        if result == null || js.isUndefined(result.asInstanceOf[js.Any]) then None
        else Some(AliasRow.fromJs(result.asInstanceOf[js.Dynamic]).instanceId)
      }
    }

  override def unbindAll(aliases: Chunk[Alias]): ZIO[Any, MechanoidError, Long] =
    if aliases.isEmpty then ZIO.succeed(0L)
    else
      ZIO.async[Any, MechanoidError, Long] { cb =>
        val tx      = db.transaction(js.Array(Idb.AliasesStore), IDBTransactionMode.readwrite)
        val store   = tx.objectStore(Idb.AliasesStore)
        val n       = aliases.size
        val exist   = new Array[Boolean](n)
        var done    = 0
        var settled = false

        def fail(error: MechanoidError): Unit =
          if !settled then
            settled = true
            cb(ZIO.fail(error))

        def afterGets(): Unit =
          aliases.zipWithIndex.foreach { (alias, i) =>
            if exist(i) then store.delete(AliasRow.keyOf(alias))
          }

        aliases.zipWithIndex.foreach { (alias, i) =>
          val req = store.get(AliasRow.keyOf(alias))
          req.onsuccess = (_: dom.Event) =>
            val result = req.result
            exist(i) = !(result == null || js.isUndefined(result.asInstanceOf[js.Any]))
            done += 1
            if done == n then afterGets()
          req.onerror = (_: dom.Event) => fail(PersistenceError(s"IndexedDB alias get failed: ${req.error}"))
        }

        tx.oncomplete = (_: dom.Event) =>
          if !settled then
            settled = true
            cb(ZIO.succeed(exist.count(identity).toLong))
        tx.onerror = (_: dom.Event) => fail(PersistenceError(s"IndexedDB unbindAll failed: ${tx.error}"))
        tx.onabort = (_: dom.Event) => fail(PersistenceError(s"IndexedDB unbindAll aborted: ${tx.error}"))
      }

  override def aliasesOf(
      instanceId: String,
      namespace: Option[String] = None,
  ): ZIO[Any, MechanoidError, Chunk[Alias]] =
    Idb.txn(db, Seq(Idb.AliasesStore), IDBTransactionMode.readonly) { tx =>
      val store = Idb.store(tx, Idb.AliasesStore)
      val index = store.index("byInstance")
      Idb.request(index.getAll(instanceId)).map { result =>
        val rows = result.asInstanceOf[js.Array[js.Dynamic]].toList.map(AliasRow.fromJs)
        val all  = Chunk.fromIterable(rows.map(_.toAlias))
        namespace match
          case Some(ns) => all.filter(_.namespace == ns)
          case None     => all
      }
    }

  override def unbindInstance(instanceId: String): ZIO[Any, MechanoidError, Long] =
    aliasesOf(instanceId).flatMap(unbindAll)
end IndexedDbInstanceIndex

object IndexedDbInstanceIndex:

  final case class AliasRow(
      key: String,
      namespace: String,
      aliasKey: String,
      instanceId: String,
      createdAtEpoch: Long,
  ):
    def toAlias: Alias = Alias(namespace, aliasKey)

    def toJs: js.Dynamic =
      js.Dynamic.literal(
        key = key,
        namespace = namespace,
        aliasKey = aliasKey,
        instanceId = instanceId,
        createdAtEpoch = createdAtEpoch.toDouble,
      )
  end AliasRow

  object AliasRow:
    def keyOf(alias: Alias): String = s"${alias.namespace}:${alias.key}"

    def fromAlias(alias: Alias, instanceId: String, now: Instant): AliasRow =
      AliasRow(keyOf(alias), alias.namespace, alias.key, instanceId, now.toEpochMilli)

    def fromJs(raw: js.Dynamic): AliasRow =
      AliasRow(
        key = raw.key.asInstanceOf[String],
        namespace = raw.namespace.asInstanceOf[String],
        aliasKey = raw.aliasKey.asInstanceOf[String],
        instanceId = raw.instanceId.asInstanceOf[String],
        createdAtEpoch = raw.createdAtEpoch.asInstanceOf[Double].toLong,
      )
  end AliasRow

  def make(dbName: String = "mechanoid"): ZIO[Any, MechanoidError, IndexedDbInstanceIndex] =
    Idb.open(dbName).map(new IndexedDbInstanceIndex(_))

  def layer(dbName: String = "mechanoid"): ZLayer[Any, MechanoidError, InstanceIndex[String]] =
    ZLayer.fromZIO(make(dbName))
end IndexedDbInstanceIndex
