package mechanoid.persistence.postgres

import saferis.*
import zio.*
import mechanoid.core.*
import mechanoid.persistence.{Alias, InstanceIndex}
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
      .run {
        Delete[AliasRow]
          .where(_.instanceId)
          .eq(instanceId)
          .build
          .dml
      }
      .map(_.toLong)
      .mapError(PersistenceError.fromError)
end PostgresInstanceIndex

object PostgresInstanceIndex:
  val layer: ZLayer[Transactor, Nothing, InstanceIndex[String]] =
    ZLayer.fromFunction(new PostgresInstanceIndex(_))
