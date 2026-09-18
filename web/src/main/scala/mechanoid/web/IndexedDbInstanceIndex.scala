package mechanoid.web

import org.scalajs.dom
import org.scalajs.dom.{IDBDatabase, IDBObjectStore, IDBTransactionMode}
import zio.*
import mechanoid.core.*
import mechanoid.persistence.{
  Alias,
  IndexFilter,
  IndexKey,
  IndexMeta,
  IndexPage,
  IndexQuery,
  IndexQueryEval,
  InstanceIndex,
}
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
    for
      nAliases <- aliasesOf(instanceId).flatMap(unbindAll)
      nIndexes <- indexesOf(instanceId).flatMap(unbindIndexes(_, instanceId))
    yield nAliases + nIndexes

  override def bindIndexes(
      keys: Chunk[IndexKey],
      instanceId: String,
      meta: IndexMeta,
  ): ZIO[Any, MechanoidError, Unit] =
    if keys.isEmpty then ZIO.unit
    else
      val distinct = keys.distinct
      indexTxn(distinct, instanceId) { (store, found) =>
        distinct.zipWithIndex.foreach { (key, i) =>
          val row = found(i) match
            case Some(old) =>
              old.copy(
                stateName = meta.stateName,
                touchedAtEpoch = meta.touchedAt.toEpochMilli,
                editedAtEpoch = meta.editedAt.toEpochMilli,
                rank = meta.rank,
              )
            case None => IndexRow.fromMeta(key, instanceId, meta)
          store.put(row.toJs)
        }
        0L
      }.unit

  override def unbindIndexes(keys: Chunk[IndexKey], instanceId: String): ZIO[Any, MechanoidError, Long] =
    if keys.isEmpty then ZIO.succeed(0L)
    else
      val distinct = keys.distinct
      indexTxn(distinct, instanceId) { (store, found) =>
        var n = 0L
        distinct.zipWithIndex.foreach { (key, i) =>
          if found(i).isDefined then
            store.delete(IndexRow.keyOf(key, instanceId))
            n += 1L
        }
        n
      }

  override def touchIndex(instanceId: String, meta: IndexMeta): ZIO[Any, MechanoidError, Long] =
    indexesOf(instanceId).flatMap { keys =>
      if keys.isEmpty then ZIO.succeed(0L)
      else
        indexTxn(keys, instanceId) { (store, found) =>
          var n = 0L
          found.foreach {
            case None      => ()
            case Some(old) =>
              store.put(
                old
                  .copy(
                    stateName = meta.stateName,
                    touchedAtEpoch = meta.touchedAt.toEpochMilli,
                    editedAtEpoch = meta.editedAt.toEpochMilli,
                    rank = meta.rank,
                  )
                  .toJs
              )
              n += 1L
          }
          n
        }
    }

  /** Get then mutate in the same IDB success turn. ZIO.flatMap between get and put lets the txn go inactive. */
  private def indexTxn(
      keys: Chunk[IndexKey],
      instanceId: String,
  )(afterGets: (IDBObjectStore, Array[Option[IndexRow]]) => Long): ZIO[Any, MechanoidError, Long] =
    ZIO.async[Any, MechanoidError, Long] { cb =>
      val tx      = db.transaction(js.Array(Idb.IndexesStore), IDBTransactionMode.readwrite)
      val store   = tx.objectStore(Idb.IndexesStore)
      val n       = keys.size
      val found   = new Array[Option[IndexRow]](n)
      var done    = 0
      var settled = false
      var written = 0L

      def fail(error: MechanoidError): Unit =
        if !settled then
          settled = true
          cb(ZIO.fail(error))

      def runAfterGets(): Unit =
        written = afterGets(store, found)

      keys.zipWithIndex.foreach { (key, i) =>
        val req = store.get(IndexRow.keyOf(key, instanceId))
        req.onsuccess = (_: dom.Event) =>
          val result = req.result
          found(i) =
            if result == null || js.isUndefined(result.asInstanceOf[js.Any]) then None
            else Some(IndexRow.fromJs(result.asInstanceOf[js.Dynamic]))
          done += 1
          if done == n then runAfterGets()
        req.onerror = (_: dom.Event) => fail(PersistenceError(s"IndexedDB index get failed: ${req.error}"))
      }

      tx.oncomplete = (_: dom.Event) =>
        if !settled then
          settled = true
          cb(ZIO.succeed(written))
      tx.onerror = (_: dom.Event) => fail(PersistenceError(s"IndexedDB index txn failed: ${tx.error}"))
      tx.onabort = (_: dom.Event) => fail(PersistenceError(s"IndexedDB index txn aborted: ${tx.error}"))
    }

  override def find(query: IndexQuery[String])(using Ordering[String]): ZIO[Any, MechanoidError, IndexPage[String]] =
    if query.startAfter.isDefined && query.startBefore.isDefined then
      ZIO.fail(InvalidIndexQuery("startAfter and startBefore are mutually exclusive"))
    else if query.since.isDefined && !IndexQueryEval.isClockSort(query.sort) then
      ZIO.fail(InvalidIndexQuery("since applies only to clock sorts, not Rank"))
    else coveringRows(query).map(rows => IndexQueryEval.page(rows, query))

  override def count(query: IndexQuery[String])(using Ordering[String]): ZIO[Any, MechanoidError, Long] =
    find(query.copy(limit = IndexQuery.MaxLimit)).map(_.items.size.toLong)

  override def count(key: IndexKey, filter: IndexFilter = IndexFilter.All): ZIO[Any, MechanoidError, Long] =
    coveringRows(IndexQuery(key, filter = filter, limit = IndexQuery.MaxLimit)).map { rows =>
      rows.count((_, row) => IndexQueryEval.matchesFilter(row.stateName, filter)).toLong
    }

  override def countsByState(key: IndexKey): ZIO[Any, MechanoidError, Chunk[(String, Long)]] =
    coveringRows(IndexQuery(key, limit = IndexQuery.MaxLimit)).map { rows =>
      Chunk.fromIterable(
        rows.groupBy(_._2.stateName).view.map { (name, rs) => name -> rs.size.toLong }.toSeq.sortBy(_._1)
      )
    }

  override def indexesOf(
      instanceId: String,
      namespace: Option[String] = None,
  ): ZIO[Any, MechanoidError, Chunk[IndexKey]] =
    Idb.txn(db, Seq(Idb.IndexesStore), IDBTransactionMode.readonly) { tx =>
      val store = Idb.store(tx, Idb.IndexesStore)
      val idx   = store.index("byInstance")
      Idb.request(idx.getAll(instanceId)).map { result =>
        val rows = result.asInstanceOf[js.Array[js.Dynamic]].toList.map(IndexRow.fromJs)
        val all  = Chunk.fromIterable(rows.map(r => IndexKey(r.namespace, r.indexKey)))
        namespace match
          case Some(ns) => all.filter(_.namespace == ns)
          case None     => all
      }
    }

  private def coveringRows(
      query: IndexQuery[String]
  ): ZIO[Any, MechanoidError, Chunk[(String, IndexQueryEval.Covering)]] =
    for
      covering <- loadKey(query.key)
      required <- loadRequiredIds(query.require)
    yield required match
      case None      => covering
      case Some(ids) => covering.filter((id, _) => ids.contains(id))

  private def loadRequiredIds(require: Chunk[IndexKey]): ZIO[Any, MechanoidError, Option[Set[String]]] =
    require.toList match
      case Nil    => ZIO.succeed(None)
      case h :: t =>
        ZIO.foldLeft(h :: t)(Option.empty[Set[String]]) { (acc, key) =>
          loadKey(key).map { rows =>
            val ids = rows.map(_._1).toSet
            acc match
              case None      => Some(ids)
              case Some(set) => Some(set.intersect(ids))
          }
        }

  private def loadKey(key: IndexKey): ZIO[Any, MechanoidError, Chunk[(String, IndexQueryEval.Covering)]] =
    Idb.txn(db, Seq(Idb.IndexesStore), IDBTransactionMode.readonly) { tx =>
      val store = Idb.store(tx, Idb.IndexesStore)
      val idx   = store.index("byKey")
      Idb.request(idx.getAll(js.Array(key.namespace, key.key))).map { result =>
        Chunk.fromIterable(
          result
            .asInstanceOf[js.Array[js.Dynamic]]
            .toList
            .map(IndexRow.fromJs)
            .map(r => r.instanceId -> r.toCovering)
        )
      }
    }
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

  final case class IndexRow(
      key: String,
      namespace: String,
      indexKey: String,
      instanceId: String,
      stateName: String,
      startedAtEpoch: Long,
      touchedAtEpoch: Long,
      createdAtEpoch: Long,
      editedAtEpoch: Long,
      rank: Long = 0L,
  ):
    def toCovering: IndexQueryEval.Covering =
      IndexQueryEval.Covering(
        stateName,
        Instant.ofEpochMilli(startedAtEpoch),
        Instant.ofEpochMilli(touchedAtEpoch),
        Instant.ofEpochMilli(createdAtEpoch),
        Instant.ofEpochMilli(editedAtEpoch),
        rank,
      )

    def toJs: js.Dynamic =
      js.Dynamic.literal(
        key = key,
        namespace = namespace,
        indexKey = indexKey,
        instanceId = instanceId,
        stateName = stateName,
        startedAtEpoch = startedAtEpoch.toDouble,
        touchedAtEpoch = touchedAtEpoch.toDouble,
        createdAtEpoch = createdAtEpoch.toDouble,
        editedAtEpoch = editedAtEpoch.toDouble,
        rank = rank.toDouble,
      )
  end IndexRow

  object IndexRow:
    def keyOf(key: IndexKey, instanceId: String): String = s"${key.namespace}:${key.key}:$instanceId"

    def fromMeta(key: IndexKey, instanceId: String, meta: IndexMeta): IndexRow =
      IndexRow(
        keyOf(key, instanceId),
        key.namespace,
        key.key,
        instanceId,
        meta.stateName,
        meta.startedAt.toEpochMilli,
        meta.touchedAt.toEpochMilli,
        meta.createdAt.toEpochMilli,
        meta.editedAt.toEpochMilli,
        meta.rank,
      )

    def fromJs(raw: js.Dynamic): IndexRow =
      IndexRow(
        key = raw.key.asInstanceOf[String],
        namespace = raw.namespace.asInstanceOf[String],
        indexKey = raw.indexKey.asInstanceOf[String],
        instanceId = raw.instanceId.asInstanceOf[String],
        stateName = raw.stateName.asInstanceOf[String],
        startedAtEpoch = raw.startedAtEpoch.asInstanceOf[Double].toLong,
        touchedAtEpoch = raw.touchedAtEpoch.asInstanceOf[Double].toLong,
        createdAtEpoch = raw.createdAtEpoch.asInstanceOf[Double].toLong,
        editedAtEpoch = raw.editedAtEpoch.asInstanceOf[Double].toLong,
        rank = longField(raw, "rank"),
      )

    private def longField(raw: js.Dynamic, name: String): Long =
      val v = raw.selectDynamic(name)
      if v == null || js.isUndefined(v) then 0L
      else v.asInstanceOf[Double].toLong
  end IndexRow

  def make(dbName: String = "mechanoid"): ZIO[Any, MechanoidError, IndexedDbInstanceIndex] =
    Idb.open(dbName).map(new IndexedDbInstanceIndex(_))

  def layer(dbName: String = "mechanoid"): ZLayer[Any, MechanoidError, InstanceIndex[String]] =
    ZLayer.fromZIO(make(dbName))
end IndexedDbInstanceIndex
