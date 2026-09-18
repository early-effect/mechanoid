package mechanoid.persistence.postgres

import saferis.*
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

/** PostgreSQL [[InstanceIndex]] keyed by `(namespace, alias_key)`.
  *
  * [[bindAll]] / [[unbindAll]] run in one transaction. Unique clashes become [[UniqueAliasError]] and roll the
  * transaction back.
  */
class PostgresInstanceIndex(transactor: Transactor) extends InstanceIndex[String]:

  override def bindAll(aliases: Chunk[Alias], instanceId: String): ZIO[Any, MechanoidError, Unit] =
    if aliases.isEmpty then ZIO.unit
    else
      for
        clash <- Ref.make(Option.empty[UniqueAliasError])
        _     <- transactor
          .transact {
            Clock.instant.flatMap { now =>
              ZIO.foreachDiscard(aliases)(bindOne(_, instanceId, now, clash))
            }
          }
          .catchAll { e =>
            clash.get.flatMap {
              case Some(err) => ZIO.fail(err)
              case None      => ZIO.fail(PersistenceError.fromError(e))
            }
          }
      yield ()

  private def bindOne(
      alias: Alias,
      instanceId: String,
      now: Instant,
      clash: Ref[Option[UniqueAliasError]],
  ): ZIO[ConnectionProvider & Scope, SaferisError, Unit] =
    Query[AliasRow]
      .where(_.namespace)
      .eq(alias.namespace)
      .where(_.aliasKey)
      .eq(alias.key)
      .queryOne[AliasRow]
      .flatMap {
        case Some(row) if row.instanceId == instanceId => ZIO.unit
        case Some(row)                                 => failUnique(clash, alias, row.instanceId, instanceId)
        case None                                      =>
          Insert[AliasRow]
            .value(_.namespace, alias.namespace)
            .value(_.aliasKey, alias.key)
            .value(_.instanceId, instanceId)
            .value(_.createdAt, now)
            .build
            .dml
            .unit
            .catchSome { case _: SaferisError.ConstraintViolation =>
              Query[AliasRow]
                .where(_.namespace)
                .eq(alias.namespace)
                .where(_.aliasKey)
                .eq(alias.key)
                .queryOne[AliasRow]
                .flatMap {
                  case Some(row) if row.instanceId == instanceId => ZIO.unit
                  case Some(row)                                 =>
                    failUnique(clash, alias, row.instanceId, instanceId)
                  case None =>
                    failUnique(clash, alias, "unknown", instanceId)
                }
            }
      }

  private def failUnique(
      clash: Ref[Option[UniqueAliasError]],
      alias: Alias,
      heldBy: String,
      requested: String,
  ): ZIO[Any, SaferisError, Nothing] =
    val error = UniqueAliasError(alias.namespace, alias.key, heldBy, requested)
    clash.set(Some(error)) *>
      ZIO.fail(SaferisError.QueryError(error, None))

  override def resolve(alias: Alias): ZIO[Any, MechanoidError, Option[String]] =
    transactor
      .run {
        Query[AliasRow]
          .where(_.namespace)
          .eq(alias.namespace)
          .where(_.aliasKey)
          .eq(alias.key)
          .queryOne[AliasRow]
      }
      .map(_.map(_.instanceId))
      .mapError(PersistenceError.fromError)

  override def unbindAll(aliases: Chunk[Alias]): ZIO[Any, MechanoidError, Long] =
    if aliases.isEmpty then ZIO.succeed(0L)
    else
      transactor
        .transact {
          ZIO.foldLeft(aliases)(0L) { (acc, alias) =>
            Delete[AliasRow]
              .where(_.namespace)
              .eq(alias.namespace)
              .where(_.aliasKey)
              .eq(alias.key)
              .build
              .dml
              .map(n => acc + n.toLong)
          }
        }
        .mapError(PersistenceError.fromError)

  override def aliasesOf(
      instanceId: String,
      namespace: Option[String] = None,
  ): ZIO[Any, MechanoidError, Chunk[Alias]] =
    transactor
      .run {
        namespace match
          case Some(ns) =>
            Query[AliasRow]
              .where(_.instanceId)
              .eq(instanceId)
              .where(_.namespace)
              .eq(ns)
              .query[AliasRow]
          case None =>
            Query[AliasRow]
              .where(_.instanceId)
              .eq(instanceId)
              .query[AliasRow]
      }
      .map(_.map(row => Alias(row.namespace, row.aliasKey)))
      .mapError(PersistenceError.fromError)

  override def unbindInstance(instanceId: String): ZIO[Any, MechanoidError, Long] =
    transactor
      .transact {
        for
          a <- Delete[AliasRow].where(_.instanceId).eq(instanceId).build.dml
          i <- Delete[FsmIndexRow].where(_.instanceId).eq(instanceId).build.dml
        yield a.toLong + i.toLong
      }
      .mapError(PersistenceError.fromError)

  override def bindIndexes(
      keys: Chunk[IndexKey],
      instanceId: String,
      meta: IndexMeta,
  ): ZIO[Any, MechanoidError, Unit] =
    if keys.isEmpty then ZIO.unit
    else
      transactor
        .transact {
          ZIO.foreachDiscard(keys.distinct)(bindIndexOne(_, instanceId, meta))
        }
        .mapError(PersistenceError.fromError)

  private def bindIndexOne(
      key: IndexKey,
      instanceId: String,
      meta: IndexMeta,
  ): ZIO[ConnectionProvider & Scope, SaferisError, Unit] =
    Query[FsmIndexRow]
      .where(_.namespace)
      .eq(key.namespace)
      .where(_.indexKey)
      .eq(key.key)
      .where(_.instanceId)
      .eq(instanceId)
      .queryOne[FsmIndexRow]
      .flatMap {
        case Some(_) =>
          Update[FsmIndexRow]
            .set(_.stateName, meta.stateName)
            .set(_.touchedAt, meta.touchedAt)
            .set(_.editedAt, meta.editedAt)
            .set(_.rank, meta.rank)
            .where(_.namespace)
            .eq(key.namespace)
            .where(_.indexKey)
            .eq(key.key)
            .where(_.instanceId)
            .eq(instanceId)
            .build
            .dml
            .unit
        case None =>
          Insert[FsmIndexRow]
            .value(_.namespace, key.namespace)
            .value(_.indexKey, key.key)
            .value(_.instanceId, instanceId)
            .value(_.stateName, meta.stateName)
            .value(_.startedAt, meta.startedAt)
            .value(_.touchedAt, meta.touchedAt)
            .value(_.createdAt, meta.createdAt)
            .value(_.editedAt, meta.editedAt)
            .value(_.rank, meta.rank)
            .build
            .dml
            .unit
      }

  override def unbindIndexes(keys: Chunk[IndexKey], instanceId: String): ZIO[Any, MechanoidError, Long] =
    if keys.isEmpty then ZIO.succeed(0L)
    else
      transactor
        .transact {
          ZIO.foldLeft(keys.distinct)(0L) { (acc, key) =>
            Delete[FsmIndexRow]
              .where(_.namespace)
              .eq(key.namespace)
              .where(_.indexKey)
              .eq(key.key)
              .where(_.instanceId)
              .eq(instanceId)
              .build
              .dml
              .map(n => acc + n.toLong)
          }
        }
        .mapError(PersistenceError.fromError)

  override def touchIndex(instanceId: String, meta: IndexMeta): ZIO[Any, MechanoidError, Long] =
    transactor
      .run {
        Update[FsmIndexRow]
          .set(_.stateName, meta.stateName)
          .set(_.touchedAt, meta.touchedAt)
          .set(_.editedAt, meta.editedAt)
          .set(_.rank, meta.rank)
          .where(_.instanceId)
          .eq(instanceId)
          .build
          .dml
      }
      .map(_.toLong)
      .mapError(PersistenceError.fromError)

  override def find(query: IndexQuery[String])(using Ordering[String]): ZIO[Any, MechanoidError, IndexPage[String]] =
    if query.startAfter.isDefined && query.startBefore.isDefined then
      ZIO.fail(InvalidIndexQuery("startAfter and startBefore are mutually exclusive"))
    else if query.since.isDefined && !IndexQueryEval.isClockSort(query.sort) then
      ZIO.fail(InvalidIndexQuery("since applies only to clock sorts, not Rank"))
    else coveringRows(query).map(rows => IndexQueryEval.page(rows, query))

  override def count(query: IndexQuery[String])(using Ordering[String]): ZIO[Any, MechanoidError, Long] =
    coveringRows(query).map { rows =>
      IndexQueryEval.page(rows, query.copy(limit = IndexQuery.MaxLimit)).items.size.toLong
    }

  override def count(key: IndexKey, filter: IndexFilter = IndexFilter.All): ZIO[Any, MechanoidError, Long] =
    coveringRows(IndexQuery(key, filter = filter, limit = IndexQuery.MaxLimit)).map { rows =>
      rows.count((_, row) => IndexQueryEval.matchesFilter(row.stateName, filter)).toLong
    }

  override def countsByState(key: IndexKey): ZIO[Any, MechanoidError, Chunk[(String, Long)]] =
    coveringRows(IndexQuery(key, limit = IndexQuery.MaxLimit)).map { rows =>
      Chunk.fromIterable(
        rows
          .groupBy(_._2.stateName)
          .view
          .map { (name, rs) => name -> rs.size.toLong }
          .toSeq
          .sortBy(_._1)
      )
    }

  override def indexesOf(
      instanceId: String,
      namespace: Option[String] = None,
  ): ZIO[Any, MechanoidError, Chunk[IndexKey]] =
    transactor
      .run {
        namespace match
          case Some(ns) =>
            Query[FsmIndexRow]
              .where(_.instanceId)
              .eq(instanceId)
              .where(_.namespace)
              .eq(ns)
              .query[FsmIndexRow]
          case None =>
            Query[FsmIndexRow].where(_.instanceId).eq(instanceId).query[FsmIndexRow]
      }
      .map(_.map(row => IndexKey(row.namespace, row.indexKey)))
      .mapError(PersistenceError.fromError)

  private def coveringRows(
      query: IndexQuery[String]
  ): ZIO[Any, MechanoidError, Chunk[(String, IndexQueryEval.Covering)]] =
    query.filter match
      case IndexFilter.Only(names) if names.isEmpty => ZIO.succeed(Chunk.empty)
      case _                                        =>
        for
          covering <- loadCovering(query)
          required <- loadRequiredIds(query.require)
        yield required match
          case None      => covering
          case Some(ids) => covering.filter((id, _) => ids.contains(id))

  /** Access-path covering rows. `Only` / rank bounds are pushed into SQL (`state_name IN`, `rank >=` / `<=`). */
  private def loadCovering(
      query: IndexQuery[String]
  ): ZIO[Any, MechanoidError, Chunk[(String, IndexQueryEval.Covering)]] =
    transactor
      .run {
        val base = Query[FsmIndexRow]
          .where(_.namespace)
          .eq(query.key.namespace)
          .where(_.indexKey)
          .eq(query.key.key)
        val withState = query.filter match
          case IndexFilter.Only(names) if names.nonEmpty   => base.where(_.stateName).inList(names)
          case IndexFilter.Except(names) if names.nonEmpty => base.where(_.stateName).notInList(names)
          case _                                           => base
        val withMin = query.rankMin match
          case Some(n) => withState.where(_.rank).gte(n)
          case None    => withState
        val withMax = query.rankMax match
          case Some(n) => withMin.where(_.rank).lte(n)
          case None    => withMin
        withMax.query[FsmIndexRow]
      }
      .map(_.map(toCovering))
      .mapError(PersistenceError.fromError)

  /** Id-only posting lists for `require` keys. Intersection is set algebra in Scala. */
  private def loadRequiredIds(require: Chunk[IndexKey]): ZIO[Any, MechanoidError, Option[Set[String]]] =
    require.toList match
      case Nil    => ZIO.succeed(None)
      case h :: t =>
        ZIO
          .foldLeft(h :: t)(Option.empty[Set[String]]) { (acc, key) =>
            loadIds(key).map { ids =>
              acc match
                case None      => Some(ids)
                case Some(set) => Some(set.intersect(ids))
            }
          }
          .map(_.orElse(Some(Set.empty)))

  private def loadIds(key: IndexKey): ZIO[Any, MechanoidError, Set[String]] =
    transactor
      .run {
        Query[FsmIndexRow]
          .where(_.namespace)
          .eq(key.namespace)
          .where(_.indexKey)
          .eq(key.key)
          .query[FsmIndexRow]
      }
      .map(_.map(_.instanceId).toSet)
      .mapError(PersistenceError.fromError)

  private def toCovering(row: FsmIndexRow): (String, IndexQueryEval.Covering) =
    row.instanceId -> IndexQueryEval.Covering(
      row.stateName,
      row.startedAt,
      row.touchedAt,
      row.createdAt,
      row.editedAt,
      row.rank,
    )
end PostgresInstanceIndex

object PostgresInstanceIndex:
  val layer: ZLayer[Transactor, Nothing, InstanceIndex[String]] =
    ZLayer.fromFunction(new PostgresInstanceIndex(_))
