package mechanoid.persistence.postgres

import saferis.*
import saferis.Schema.*
import saferis.postgres.given
import zio.*

/** PostgreSQL schema initializer for Mechanoid.
  *
  * Provides utilities to create and verify the database schema required by Mechanoid's PostgreSQL persistence layer.
  * Uses Saferis Schema DSL for table creation and Schema.verify for validation.
  *
  * Missing tables are created individually (verify-or-create per table) so an existing database that already has
  * events/snapshots can pick up `fsm_aliases` without rebuilding the rest.
  *
  * ==Usage==
  * {{{
  * // Initialize schema (creates if missing, verifies if exists)
  * PostgresSchema.initialize.provide(transactorLayer)
  * }}}
  */
object PostgresSchema:

  /** Result of schema initialization. */
  enum InitResult:
    /** At least one table was created using Saferis DDL. */
    case Created

    /** All tables already existed and passed verification. */
    case Verified

  // ==================== Schema Definitions using Saferis Schema DSL ====================

  private val eventsSchema = Schema[EventRow[String]]
    .withUniqueConstraint(_.instanceId)
    .and(_.sequenceNr)
    .named("uq_fsm_events_instance_seq")
    .withIndex(_.instanceId)
    .and(_.sequenceNr)
    .named("idx_fsm_events_instance")

  private val snapshotsSchema = Schema[SnapshotRow[String]]

  private val timeoutsSchema = Schema[TimeoutRow]
    .withIndex(_.deadline)
    .named("idx_timeouts_deadline")

  private val locksSchema = Schema[LockRow]
    .withIndex(_.expiresAt)
    .named("idx_locks_expired")

  private val leasesSchema = Schema[LeaseRow]
    .withIndex(_.expiresAt)
    .named("idx_leases_expires")

  private val aliasesSchema = Schema[AliasRow]
    .withIndex(_.instanceId)
    .named("idx_fsm_aliases_instance")
    .withIndex(_.instanceId)
    .and(_.namespace)
    .named("idx_fsm_aliases_instance_ns")

  private final case class ManagedTable(
      verify: ZIO[ConnectionProvider & Scope, SaferisError, Unit],
      ddl: SqlFragment,
  )

  private val managedTables: List[ManagedTable] = List(
    ManagedTable(Schema[EventRow[String]].verify, eventsSchema.ddl()),
    ManagedTable(Schema[SnapshotRow[String]].verify, snapshotsSchema.ddl()),
    ManagedTable(Schema[TimeoutRow].verify, timeoutsSchema.ddl()),
    ManagedTable(Schema[LockRow].verify, locksSchema.ddl()),
    ManagedTable(Schema[LeaseRow].verify, leasesSchema.ddl()),
    ManagedTable(Schema[AliasRow].verify, aliasesSchema.ddl()),
  )

  /** Initialize the schema: creates missing tables, verifies tables that already exist.
    *
    * @return
    *   `InitResult.Created` if any table was created, `InitResult.Verified` if every table already existed
    */
  def initialize: ZIO[Transactor, SaferisError, InitResult] =
    ZIO.serviceWithZIO[Transactor] { xa =>
      ensureAll(xa).map(created => if created then InitResult.Created else InitResult.Verified)
    }

  /** Create missing tables.
    *
    * @return
    *   true if any table was created, false if they all already existed
    */
  def createIfNotExists: ZIO[Transactor, SaferisError, Boolean] =
    ZIO.serviceWithZIO[Transactor](ensureAll)

  /** Verify the existing schema matches expectations.
    *
    * Does not create tables - only validates existing structure.
    */
  val verify: ZIO[Transactor, SaferisError, Unit] =
    ZIO.serviceWithZIO[Transactor](verifyAllSchemas)

  private def ensureAll(xa: Transactor): ZIO[Any, SaferisError, Boolean] =
    ZIO
      .foldLeft(managedTables)(false) { (anyCreated, table) =>
        ensureTable(xa, table).map(_ || anyCreated)
      }

  private def ensureTable(xa: Transactor, table: ManagedTable): ZIO[Any, SaferisError, Boolean] =
    xa.run(table.verify)
      .as(false)
      .catchSome {
        case SaferisError.SchemaValidation(issues) if issues.exists(_.isInstanceOf[SchemaIssue.TableNotFound]) =>
          xa.run(table.ddl.dml).as(true)
      }

  private def verifyAllSchemas(xa: Transactor): ZIO[Any, SaferisError, Unit] =
    ZIO.foreachDiscard(managedTables)(t => xa.run(t.verify))

end PostgresSchema
