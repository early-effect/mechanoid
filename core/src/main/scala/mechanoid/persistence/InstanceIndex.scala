package mechanoid.persistence

import zio.*
import zio.stream.ZStream
import mechanoid.core.MechanoidError

/** Unique secondary-key index from [[Alias]] to FSM instance id.
  *
  * Parallel to [[EventStore]]: the store still keys events by instance id. This index answers "which instance owns
  * Campaign X?" in one lookup.
  *
  * ==Uniqueness==
  *
  * Each `(namespace, key)` maps to at most one instance. Binding an alias already held by a different instance fails
  * with [[mechanoid.core.UniqueAliasError]]. Rebinding the same alias to the same instance is a no-op.
  *
  * ==Batching==
  *
  * Prefer [[bindAll]] / [[unbindAll]] when attaching many keys (an initiative with ~100 campaign ids). Implementations
  * should do that work in one in-memory update or one database transaction.
  *
  * @tparam Id
  *   The FSM instance identifier type
  */
trait InstanceIndex[Id]:

  /** Bind one alias to an instance. See [[bindAll]]. */
  def bind(alias: Alias, instanceId: Id): ZIO[Any, MechanoidError, Unit] =
    bindAll(Chunk(alias), instanceId)

  /** Bind many aliases to one instance in one operation.
    *
    * Fails with [[mechanoid.core.UniqueAliasError]] if any alias is held by a different instance. Empty input is a
    * no-op.
    */
  def bindAll(aliases: Chunk[Alias], instanceId: Id): ZIO[Any, MechanoidError, Unit]

  /** Resolve an alias to an instance id, if bound. */
  def resolve(alias: Alias): ZIO[Any, MechanoidError, Option[Id]]

  /** Remove one alias binding. Returns true if a row was removed. */
  def unbind(alias: Alias): ZIO[Any, MechanoidError, Boolean] =
    unbindAll(Chunk(alias)).map(_ > 0)

  /** Remove many alias bindings. Returns how many rows were removed. Empty input is a no-op. */
  def unbindAll(aliases: Chunk[Alias]): ZIO[Any, MechanoidError, Long]

  /** List aliases currently bound to this instance, optionally filtered by namespace. */
  def aliasesOf(instanceId: Id, namespace: Option[String] = None): ZIO[Any, MechanoidError, Chunk[Alias]]

  /** Remove every alias bound to this instance. Returns how many rows were removed. */
  def unbindInstance(instanceId: Id): ZIO[Any, MechanoidError, Long]

  /** Bind non-unique index keys to an instance. Duplicate keys in `keys` collapse. Empty input is a no-op. */
  def bindIndexes(keys: Chunk[IndexKey], instanceId: Id, meta: IndexMeta): ZIO[Any, MechanoidError, Unit]

  /** Remove index keys for an instance. Returns how many rows were removed. */
  def unbindIndexes(keys: Chunk[IndexKey], instanceId: Id): ZIO[Any, MechanoidError, Long]

  /** Update leaf name, touchedAt, and editedAt for every index row of this instance. startedAt/createdAt stay. */
  def touchIndex(instanceId: Id, meta: IndexMeta): ZIO[Any, MechanoidError, Long]

  /** Keyed lookup. Implementations must not scan other keys. Requires [[Ordering]] for the instance-id tiebreaker. */
  def find(query: IndexQuery[Id])(using Ordering[Id]): ZIO[Any, MechanoidError, IndexPage[Id]]

  def find[S](q: IndexQueryBuilder[S])(using Ordering[Id]): ZIO[Any, MechanoidError, IndexPage[Id]] =
    find(q.toQuery[Id])

  def count(query: IndexQuery[Id])(using Ordering[Id]): ZIO[Any, MechanoidError, Long]

  def count[S](q: IndexQueryBuilder[S])(using Ordering[Id]): ZIO[Any, MechanoidError, Long] =
    count(q.toQuery[Id])

  def count(key: IndexKey, filter: IndexFilter = IndexFilter.All): ZIO[Any, MechanoidError, Long]

  def countsByState(key: IndexKey): ZIO[Any, MechanoidError, Chunk[(String, Long)]]

  def indexesOf(instanceId: Id, namespace: Option[String] = None): ZIO[Any, MechanoidError, Chunk[IndexKey]]

  /** Unfold [[find]] with exclusive `startAfter`, one page per pull. */
  def findPages(query: IndexQuery[Id])(using Ordering[Id]): ZStream[Any, MechanoidError, IndexPage[Id]] =
    ZStream.unfoldZIO((query.startAfter, 0, false)) { case (cursor, pageNum, done) =>
      if done then ZIO.succeed(None)
      else
        find(query.copy(startAfter = cursor, startBefore = if cursor.isDefined then None else query.startBefore)).map {
          page =>
            val numbered = page.copy(pageNumber = pageNum)
            if numbered.items.isEmpty then None
            else Some((numbered, (numbered.cursor, pageNum + 1, !numbered.hasMore)))
        }
    }
end InstanceIndex
