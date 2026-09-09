package mechanoid.persistence

import zio.*
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
end InstanceIndex
