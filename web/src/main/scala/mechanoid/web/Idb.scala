package mechanoid.web

import org.scalajs.dom
import org.scalajs.dom.{
  IDBCreateObjectStoreOptions,
  IDBDatabase,
  IDBFactory,
  IDBObjectStore,
  IDBTransaction,
  IDBTransactionMode,
}
import zio.*
import mechanoid.core.*
import scala.scalajs.js

/** Low-level IndexedDB open / transaction helpers. */
object Idb:

  val DbVersion      = 1
  val EventsStore    = "events"
  val SnapshotsStore = "snapshots"
  val TimeoutsStore  = "timeouts"
  val LocksStore     = "locks"

  private def keyPathOpts(path: String): IDBCreateObjectStoreOptions =
    js.Dynamic.literal(keyPath = path).asInstanceOf[IDBCreateObjectStoreOptions]

  def open(dbName: String): ZIO[Any, MechanoidError, IDBDatabase] =
    ZIO.async[Any, MechanoidError, IDBDatabase] { cb =>
      // Prefer globalThis.indexedDB (Node + fake-indexeddb); avoid dom.window (needs a browser).
      val raw                 = js.Dynamic.global.indexedDB
      val factory: IDBFactory =
        if js.isUndefined(raw) || raw == null then sys.error("IndexedDB is not available in this environment")
        else raw.asInstanceOf[IDBFactory]
      val req = factory.open(dbName, DbVersion)
      req.onupgradeneeded = (event: dom.Event) =>
        val db = event.target.asInstanceOf[js.Dynamic].result.asInstanceOf[IDBDatabase]
        if !db.objectStoreNames.contains(EventsStore) then
          val events = db.createObjectStore(EventsStore, keyPathOpts("key"))
          events.createIndex("byInstance", "instanceId")
        if !db.objectStoreNames.contains(SnapshotsStore) then
          db.createObjectStore(SnapshotsStore, keyPathOpts("instanceId"))
        if !db.objectStoreNames.contains(TimeoutsStore) then
          val timeouts = db.createObjectStore(TimeoutsStore, keyPathOpts("instanceId"))
          timeouts.createIndex("byDeadline", "deadlineEpoch")
        if !db.objectStoreNames.contains(LocksStore) then db.createObjectStore(LocksStore, keyPathOpts("instanceId"))
      req.onsuccess = (_: dom.Event) => cb(ZIO.succeed(req.result.asInstanceOf[IDBDatabase]))
      req.onerror = (_: dom.Event) => cb(ZIO.fail(PersistenceError(s"IndexedDB open failed: ${req.error}")))
    }

  def txn[A](
      db: IDBDatabase,
      stores: Seq[String],
      mode: IDBTransactionMode,
  )(use: IDBTransaction => ZIO[Any, MechanoidError, A]): ZIO[Any, MechanoidError, A] =
    // Issue requests inside `use` via [[request]]. Do not await `oncomplete` after `use`
    // returns: the browser may have already fired it, and a late waiter hangs forever.
    ZIO.succeed(db.transaction(js.Array(stores*), mode)).flatMap(use)

  def request[A](req: dom.IDBRequest[?, A]): ZIO[Any, MechanoidError, A] =
    ZIO.async[Any, MechanoidError, A] { cb =>
      req.onsuccess = (_: dom.Event) => cb(ZIO.succeed(req.result))
      req.onerror = (_: dom.Event) => cb(ZIO.fail(PersistenceError(s"IndexedDB request failed: ${req.error}")))
    }

  def store(tx: IDBTransaction, name: String): IDBObjectStore =
    tx.objectStore(name)
end Idb
