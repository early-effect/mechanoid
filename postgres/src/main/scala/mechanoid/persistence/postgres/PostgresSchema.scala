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
  * ==Usage==
  * {{{
  * // Initialize schema (creates if missing, verifies if exists)
  * PostgresSchema.initialize.provide(transactorLayer)
  * }}}
  */
object PostgresSchema:

  /** Result of schema initialization. */
  enum InitResult:
    /** Tables were created using Saferis DDL. */
    case Created

    /** Tables already existed and passed verification. */
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

  /** Initialize the schema: creates tables if they don't exist, verifies if they do.
    *
    * @return
    *   `InitResult.Created` if tables were created, `InitResult.Verified` if existing tables passed validation
    */
  def initialize: ZIO[Transactor, SaferisError, InitResult] =
    ZIO.serviceWithZIO[Transactor] { xa =>
      verifyAllSchemas(xa)
        .as(InitResult.Verified)
        .catchSome {
          case SaferisError.SchemaValidation(issues) if issues.exists(_.isInstanceOf[SchemaIssue.TableNotFound]) =>
            createSchema(xa).as(InitResult.Created)
        }
    }

  /** Create the schema if tables don't exist.
    *
    * @return
    *   true if tables were created, false if they already existed
    */
  def createIfNotExists: ZIO[Transactor, SaferisError, Boolean] =
    ZIO.serviceWithZIO[Transactor] { xa =>
      verifyAllSchemas(xa)
        .as(false)
        .catchSome {
          case SaferisError.SchemaValidation(issues) if issues.exists(_.isInstanceOf[SchemaIssue.TableNotFound]) =>
            createSchema(xa).as(true)
        }
    }

  /** Verify the existing schema matches expectations.
    *
    * Does not create tables - only validates existing structure.
    */
  def verify: ZIO[Transactor, SaferisError, Unit] =
    ZIO.serviceWithZIO[Transactor](verifyAllSchemas)

  // ==================== Private Implementation ====================

  private def createSchema(xa: Transactor): ZIO[Any, SaferisError, Unit] =
    for
      _ <- xa.run(eventsSchema.ddl().dml)
      _ <- xa.run(snapshotsSchema.ddl().dml)
      _ <- xa.run(timeoutsSchema.ddl().dml)
      _ <- xa.run(locksSchema.ddl().dml)
      _ <- xa.run(leasesSchema.ddl().dml)
    yield ()

  private def verifyAllSchemas(xa: Transactor): ZIO[Any, SaferisError, Unit] =
    for
      _ <- xa.run(Schema[EventRow[String]].verify)
      _ <- xa.run(Schema[SnapshotRow[String]].verify)
      _ <- xa.run(Schema[TimeoutRow].verify)
      _ <- xa.run(Schema[LockRow].verify)
      _ <- xa.run(Schema[LeaseRow].verify)
    yield ()

end PostgresSchema
