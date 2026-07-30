package mechanoid.examples.heartbeat

import zio.*
import org.postgresql.ds.PGSimpleDataSource
import saferis.{ConnectionProvider, Transactor}
import mechanoid.*
import mechanoid.postgres.*

// ============================================
// Layers
// ============================================

object Layers:

  val transactor: ZLayer[Any, Throwable, Transactor] =
    ZLayer.scoped {
      for
        url      <- System.env("DATABASE_URL").someOrFail(new RuntimeException("DATABASE_URL not set"))
        user     <- System.env("DATABASE_USER").someOrFail(new RuntimeException("DATABASE_USER not set"))
        password <- System.env("DATABASE_PASSWORD").someOrFail(new RuntimeException("DATABASE_PASSWORD not set"))
        ds =
          val d = PGSimpleDataSource()
          d.setURL(url)
          d.setUser(user)
          d.setPassword(password)
          d
        cp = ConnectionProvider.FromDataSource(ds)
        xa <- ZIO.service[Transactor].provideLayer(ZLayer.succeed(cp) >>> Transactor.default)
      yield xa
    }

  // Use makeLayer with explicit types for state and event
  val eventStore: ZLayer[Transactor, Nothing, EventStore[String, ServiceState, ServiceEvent]] =
    PostgresEventStore.makeLayer[ServiceState, ServiceEvent]

  val timeoutStore: ZLayer[Transactor, Nothing, TimeoutStore[String]] =
    ZLayer.fromFunction((xa: Transactor) => PostgresTimeoutStore(xa))

  val timeoutStrategy: ZLayer[TimeoutStore[String], Nothing, TimeoutStrategy[String]] =
    DurableTimeoutStrategy.layer[String]

  val lockingStrategy: ZLayer[Any, Nothing, LockingStrategy[String]] =
    ZLayer.succeed(OptimisticLockingStrategy.make[String])
end Layers

// ============================================
// Main Application
// ============================================

object Main extends ZIOAppDefault:

  val app: ZIO[
    Transactor & EventStore[String, ServiceState, ServiceEvent] & TimeoutStore[String] & TimeoutStrategy[String] &
      LockingStrategy[String] & Scope,
    Any,
    Unit,
  ] =
    for
      _ <- ZIO.logInfo("Starting Mechanoid service...")

      // Initialize database schema
      _      <- ZIO.logInfo("Initializing database schema...")
      result <- PostgresSchema.initialize
      _      <- ZIO.logInfo(s"Schema initialization result: $result")

      // Get dependencies from environment
      eventStore      <- ZIO.service[EventStore[String, ServiceState, ServiceEvent]]
      timeoutStore    <- ZIO.service[TimeoutStore[String]]
      timeoutStrategy <- ZIO.service[TimeoutStrategy[String]]
      lockingStrategy <- ZIO.service[LockingStrategy[String]]

      // Create FSM runtime for our simple service
      instanceId = "service-instance-1"

      // JsonCodec for ServiceState/ServiceEvent is auto-derived from Finite
      // via `import mechanoid.postgres.*` which brings in finiteJsonCodec
      runtime <- FSMRuntime(
        instanceId,
        ServiceFSM.machine,
        ServiceState.Stopped,
      ).provideSomeLayer[Scope](
        ZLayer.succeed(eventStore) ++
          ZLayer.succeed(timeoutStrategy) ++
          ZLayer.succeed(lockingStrategy)
      )

      // Configure and start the sweeper
      sweeperConfig = TimeoutSweeperConfig()
        .withSweepInterval(5.seconds)
        .withBatchSize(50)
        .withJitterFactor(0.1)

      _ <- ZIO.logInfo(s"Starting TimeoutSweeper with configuration:")
      _ <- ZIO.logInfo(s"  sweepInterval: ${sweeperConfig.sweepInterval}")
      _ <- ZIO.logInfo(s"  batchSize: ${sweeperConfig.batchSize}")
      _ <- ZIO.logInfo(s"  jitterFactor: ${sweeperConfig.jitterFactor}")
      _ <- ZIO.logInfo(s"  claimDuration: ${sweeperConfig.claimDuration}")
      _ <- ZIO.logInfo(s"  backoffOnEmpty: ${sweeperConfig.backoffOnEmpty}")
      _ <- ZIO.logInfo(s"  nodeId: ${sweeperConfig.nodeId}")

      _ <- TimeoutSweeper.make(
        config = sweeperConfig,
        timeoutStore = timeoutStore,
        runtime = runtime,
      )

      _ <- ZIO.logInfo("TimeoutSweeper started successfully")

      // Start the FSM - effects run inline, no command handling needed
      _     <- runtime.send(ServiceEvent.Start)
      state <- runtime.currentState
      _     <- ZIO.logInfo(s"Service FSM started, current state: $state")

      // Periodically log current state so we can watch the FSM branch
      _ <- (for
        state <- runtime.currentState
        _     <- ZIO.logInfo(s"📊 Current state: $state")
      yield ()).repeat(Schedule.spaced(2.seconds)).forkScoped

      // Run forever (sweeper runs in background via forkScoped)
      _ <- ZIO.logInfo("Service running. Watch for state transitions! Press Ctrl+C to stop.")
      _ <- ZIO.logInfo("  - Started: Normal operation (10s heartbeat)")
      _ <- ZIO.logInfo("  - Degraded: Faster checks (3s) for recovery")
      _ <- ZIO.logInfo("  - Critical: Human intervenes after 15s")
      _ <- ZIO.never
    yield ()

  override def run =
    app
      .provideSome[Scope](
        Layers.transactor,
        Layers.eventStore,
        Layers.timeoutStore,
        Layers.timeoutStrategy,
        Layers.lockingStrategy,
      )
      .tapErrorCause(cause => ZIO.logErrorCause("Service failed", cause))
end Main
