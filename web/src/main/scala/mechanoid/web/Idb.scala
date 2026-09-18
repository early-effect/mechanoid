package mechanoid.web

import org.scalajs.dom
import org.scalajs.dom.{
  ErrorEvent,
  IDBCreateObjectStoreOptions,
  IDBDatabase,
  IDBEvent,
  IDBFactory,
  IDBKeyPath,
  IDBObjectStore,
  IDBTransaction,
  IDBTransactionMode,
  IDBVersionChangeEvent,
}
import zio.*
import mechanoid.core.*
import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobalScope

/** Low-level IndexedDB open / transaction helpers. */
object Idb:

  val DbVersion      = 5
  val EventsStore    = "events"
  val SnapshotsStore = "snapshots"
  val TimeoutsStore  = "timeouts"
  val LocksStore     = "locks"
  val AliasesStore   = "aliases"
  val IndexesStore   = "indexes"

  @js.native
  @JSGlobalScope
  private object IndexedDbGlobal extends js.Object:
    def indexedDB: js.UndefOr[IDBFactory] = js.native

  private def factory: IDBFactory =
    IndexedDbGlobal.indexedDB.getOrElse(
      sys.error("IndexedDB is not available in this environment")
    )

  private def keyPathOpts(path: String): IDBCreateObjectStoreOptions =
    new IDBCreateObjectStoreOptions:
      override val keyPath: IDBKeyPath = path

  private def keyPathOpts(path: js.Array[String]): IDBCreateObjectStoreOptions =
    new IDBCreateObjectStoreOptions:
      override val keyPath: IDBKeyPath = path

  def open(dbName: String): ZIO[Any, MechanoidError, IDBDatabase] =
    ZIO.async[Any, MechanoidError, IDBDatabase] { cb =>
      val req = factory.open(dbName, DbVersion)
      req.onupgradeneeded = (event: IDBVersionChangeEvent) =>
        val db = event.target.result
        if !db.objectStoreNames.contains(EventsStore) then
          val events = db.createObjectStore(EventsStore, keyPathOpts("key"))
          events.createIndex("byInstance", "instanceId")
        if !db.objectStoreNames.contains(SnapshotsStore) then
          db.createObjectStore(SnapshotsStore, keyPathOpts("instanceId"))
        if event.oldVersion < 5 && db.objectStoreNames.contains(TimeoutsStore) then db.deleteObjectStore(TimeoutsStore)
        if !db.objectStoreNames.contains(TimeoutsStore) then
          val timeouts = db.createObjectStore(
            TimeoutsStore,
            keyPathOpts(js.Array("instanceId", "timeoutKey")),
          )
          timeouts.createIndex("byDeadline", "deadlineEpoch")
        if !db.objectStoreNames.contains(LocksStore) then db.createObjectStore(LocksStore, keyPathOpts("instanceId"))
        if !db.objectStoreNames.contains(AliasesStore) then
          val aliases = db.createObjectStore(AliasesStore, keyPathOpts("key"))
          aliases.createIndex("byInstance", "instanceId")
        if !db.objectStoreNames.contains(IndexesStore) then
          val indexes = db.createObjectStore(IndexesStore, keyPathOpts("key"))
          indexes.createIndex("byKey", js.Array("namespace", "indexKey"))
          indexes.createIndex("byInstance", "instanceId")
      req.onsuccess = (_: IDBEvent[IDBDatabase]) => cb(ZIO.succeed(req.result))
      req.onerror = (_: ErrorEvent) => cb(ZIO.fail(PersistenceError(s"IndexedDB open failed: ${req.error}")))
    }

  def txn[A](
      db: IDBDatabase,
      stores: Seq[String],
      mode: IDBTransactionMode,
  )(use: IDBTransaction => ZIO[Any, MechanoidError, A]): ZIO[Any, MechanoidError, A] =
    ZIO.succeed(db.transaction(js.Array(stores*), mode)).flatMap(use)

  def request[A](req: dom.IDBRequest[?, A]): ZIO[Any, MechanoidError, A] =
    ZIO.async[Any, MechanoidError, A] { cb =>
      req.onsuccess = (_: IDBEvent[A]) => cb(ZIO.succeed(req.result))
      req.onerror = (_: ErrorEvent) => cb(ZIO.fail(PersistenceError(s"IndexedDB request failed: ${req.error}")))
    }

  def store(tx: IDBTransaction, name: String): IDBObjectStore =
    tx.objectStore(name)
end Idb
