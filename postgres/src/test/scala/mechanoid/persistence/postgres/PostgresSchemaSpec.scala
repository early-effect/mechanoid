package mechanoid.persistence.postgres

import saferis.*
import zio.*
import zio.test.*
import mechanoid.PostgresTestContainer.DataSourceProvider

object PostgresSchemaSpec extends ZIOSpecDefault:

  // Use a plain connection provider without auto-initialization for these tests
  val plainXaLayer = DataSourceProvider.default >>> Transactor.default

  private def resetSchema(xa: Transactor) =
    xa.run(sql"DROP SCHEMA IF EXISTS public CASCADE".dml) *>
      xa.run(sql"CREATE SCHEMA public".dml)

  // Helper to create all required tables except the one being tested
  private def createOtherTables(xa: Transactor) =
    for
      _ <- xa.run(sql"""CREATE TABLE fsm_snapshots (
             instance_id TEXT PRIMARY KEY,
             state_data JSONB NOT NULL,
             sequence_nr BIGINT NOT NULL,
             created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
           )""".dml)
      _ <- xa.run(sql"""CREATE TABLE scheduled_timeouts (
             instance_id TEXT PRIMARY KEY,
             state_hash INT NOT NULL,
             sequence_nr BIGINT NOT NULL,
             deadline TIMESTAMPTZ NOT NULL,
             created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
             claimed_by TEXT,
             claimed_until TIMESTAMPTZ
           )""".dml)
      _ <- xa.run(sql"""CREATE TABLE fsm_instance_locks (
             instance_id TEXT PRIMARY KEY,
             node_id TEXT NOT NULL,
             acquired_at TIMESTAMPTZ NOT NULL,
             expires_at TIMESTAMPTZ NOT NULL
           )""".dml)
      _ <- xa.run(sql"""CREATE TABLE leases (
             key TEXT PRIMARY KEY,
             holder TEXT NOT NULL,
             expires_at TIMESTAMPTZ NOT NULL,
             acquired_at TIMESTAMPTZ NOT NULL
           )""".dml)
      _ <- xa.run(sql"""CREATE TABLE fsm_aliases (
             namespace TEXT NOT NULL,
             alias_key TEXT NOT NULL,
             instance_id TEXT NOT NULL,
             created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
             PRIMARY KEY (namespace, alias_key)
           )""".dml)
      _ <- xa.run(sql"""CREATE TABLE fsm_indexes (
             namespace TEXT NOT NULL,
             index_key TEXT NOT NULL,
             instance_id TEXT NOT NULL,
             state_name TEXT NOT NULL,
             started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
             touched_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
             created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
             edited_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
             rank BIGINT NOT NULL DEFAULT 0,
             PRIMARY KEY (namespace, index_key, instance_id)
           )""".dml)
    yield ()

  def spec = (
    suite("PostgresSchema")(
      test("initialize creates tables on empty database") {
        for result <- PostgresSchema.initialize
        yield assertTrue(result == PostgresSchema.InitResult.Created)
      },
      test("initialize verifies existing tables on second call") {
        for
          result1 <- PostgresSchema.initialize
          result2 <- PostgresSchema.initialize
        yield assertTrue(
          result1 == PostgresSchema.InitResult.Created,
          result2 == PostgresSchema.InitResult.Verified,
        )
      },
      test("createIfNotExists returns true when tables created") {
        for created <- PostgresSchema.createIfNotExists
        yield assertTrue(created)
      },
      test("createIfNotExists returns false when tables exist") {
        for
          _       <- PostgresSchema.createIfNotExists
          created <- PostgresSchema.createIfNotExists
        yield assertTrue(!created)
      },
      test("verify passes when schema is correct") {
        for
          _      <- PostgresSchema.createIfNotExists
          result <- PostgresSchema.verify.either
        yield assertTrue(result.isRight)
      },
      test("verify detects missing table") {
        for
          xa <- ZIO.service[Transactor]
          // Create only fsm_events, not all tables
          _ <- xa.run(sql"""CREATE TABLE fsm_events (
               id BIGSERIAL PRIMARY KEY,
               instance_id TEXT NOT NULL,
               sequence_nr BIGINT NOT NULL,
               event_data JSONB NOT NULL,
               created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
               UNIQUE (instance_id, sequence_nr)
             )""".dml)
          result <- PostgresSchema.verify.either
        yield result match
          case Left(SaferisError.SchemaValidation(issues)) =>
            assertTrue(
              issues.exists {
                case SchemaIssue.TableNotFound(name) => name == "fsm_snapshots" || name == "leases"
                case _                               => false
              }
            )
          case Left(_)  => assertTrue(false)
          case Right(_) => assertTrue(false)
      },
      test("verify detects missing column") {
        for
          xa <- ZIO.service[Transactor]
          // Create fsm_events with missing event_data column
          _ <- xa.run(sql"""CREATE TABLE fsm_events (
               id BIGSERIAL PRIMARY KEY,
               instance_id TEXT NOT NULL,
               sequence_nr BIGINT NOT NULL,
               created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
             )""".dml)
          // Create other required tables with correct schema
          _      <- createOtherTables(xa)
          result <- PostgresSchema.verify.either
        yield result match
          case Left(SaferisError.SchemaValidation(issues)) =>
            assertTrue(
              issues.exists {
                case SchemaIssue.MissingColumn("fsm_events", "event_data", _) => true
                case _                                                        => false
              }
            )
          case Left(_)  => assertTrue(false)
          case Right(_) => assertTrue(false)
      },
      test("all managed tables are verified") {
        for
          _      <- PostgresSchema.initialize
          result <- PostgresSchema.verify.either
        yield assertTrue(result.isRight)
      },
      test("initialize creates fsm_aliases when other tables already exist") {
        for
          xa <- ZIO.service[Transactor]
          _  <- xa.run(sql"""CREATE TABLE fsm_events (
               id BIGSERIAL PRIMARY KEY,
               instance_id TEXT NOT NULL,
               sequence_nr BIGINT NOT NULL,
               event_data JSONB NOT NULL,
               created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
               UNIQUE (instance_id, sequence_nr)
             )""".dml)
          _      <- createOtherTables(xa)
          _      <- xa.run(sql"DROP TABLE fsm_aliases".dml)
          result <- PostgresSchema.initialize
          verify <- PostgresSchema.verify.either
        yield assertTrue(result == PostgresSchema.InitResult.Created, verify.isRight)
      },
      test("init.sql produces schema compatible with PostgresSchema.verify") {
        for
          xa      <- ZIO.service[Transactor]
          initSql <- ZIO.attempt {
            val stream = getClass.getResourceAsStream("/init.sql")
            try scala.io.Source.fromInputStream(stream).mkString
            finally stream.close()
          }.orDie
          // Execute each statement from init.sql
          _ <- ZIO.foreach(initSql.split(";").map(_.trim).filter(_.nonEmpty)) { stmt =>
            xa.run(SqlFragment(stmt, Seq.empty).dml)
          }
          // Verify the schema created by init.sql passes Saferis verification
          result <- PostgresSchema.verify.either
        yield assertTrue(result.isRight)
      },
    ) @@ TestAspect.sequential @@ TestAspect.before {
      ZIO.serviceWithZIO[Transactor](resetSchema)
    }
  ).provideShared(plainXaLayer) @@ TestAspect.withLiveClock
end PostgresSchemaSpec
