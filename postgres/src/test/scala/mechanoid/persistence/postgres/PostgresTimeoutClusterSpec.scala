package mechanoid.persistence.postgres

import zio.*
import zio.test.*
import mechanoid.PostgresTestContainer
import mechanoid.*
import mechanoid.postgres.finiteJsonCodec
import mechanoid.persistence.timeout.*
import mechanoid.runtime.{FSMRuntime, InstanceMailbox}
import mechanoid.runtime.locking.LockingStrategy
import mechanoid.runtime.timeout.TimeoutStrategy

/** Three independent sweeper nodes, one Postgres. Every armed timeout must be delivered.
  *
  * A second send that loses the sequence releases the claim. A machine left in Waiting is the failure.
  */
object PostgresTimeoutClusterSpec extends ZIOSpecDefault:

  enum ClusterState derives Finite:
    case Idle, Waiting, Done

  enum ClusterEvent derives Finite:
    case Arm, Tick

  import ClusterState.*
  import ClusterEvent.*

  private val machine = Machine(
    assembly[ClusterState, ClusterEvent](
      (Idle via Arm to Waiting) @@ Aspect.timeout(80.millis, Tick),
      Waiting via Tick to Done,
    )
  )

  private val xaLayer           = PostgresTestContainer.DataSourceProvider.transactor
  private val eventStoreLayer   = xaLayer >>> PostgresEventStore.makeLayer[ClusterState, ClusterEvent]
  private val timeoutStoreLayer = xaLayer >>> PostgresTimeoutStore.layer
  private val stores            = eventStoreLayer ++ timeoutStoreLayer

  private def uniqueId(prefix: String) = s"$prefix-${java.util.UUID.randomUUID()}"

  private def runtimeLayers(events: EventStore[String, ClusterState, ClusterEvent], timeouts: TimeoutStore[String]) =
    val timeoutEnv = ZLayer.succeed(timeouts)
    ZLayer.succeed(events) ++
      timeoutEnv ++
      (timeoutEnv >>> TimeoutStrategy.durable[String]) ++
      LockingStrategy.optimistic[String]

  private def openExisting(
      events: EventStore[String, ClusterState, ClusterEvent],
      timeouts: TimeoutStore[String],
  )(id: String) =
    FSMRuntime.existing(id, machine, Idle).provideSome[Scope](runtimeLayers(events, timeouts))

  private def startNode(
      nodeId: String,
      events: EventStore[String, ClusterState, ClusterEvent],
      timeouts: TimeoutStore[String],
  ): ZIO[Scope, MechanoidError, TimeoutSweeper] =
    TimeoutSweeper
      .make(
        TimeoutSweeperConfig()
          .withNodeId(nodeId)
          .withSweepInterval(80.millis)
          .withJitterFactor(0.3)
          .withClaimDuration(5.seconds)
          .withDelivery(TimeoutDelivery.AtLeastOnce),
        timeouts,
        openExisting(events, timeouts),
      )
      .provideSome[Scope](InstanceMailbox.layer[String])

  def spec = suite("Postgres timeout cluster")(
    test("three sweepers deliver every expired timeout; no instance stays Waiting") {
      val n = 12
      for
        timeouts <- ZIO.service[TimeoutStore[String]]
        events   <- ZIO.service[EventStore[String, ClusterState, ClusterEvent]]
        layers = runtimeLayers(events, timeouts)
        ids <- ZIO.foreach(1 to n) { i =>
          val id = uniqueId(s"cluster-$i")
          ZIO.scoped(FSMRuntime(id, machine, Idle).flatMap(_.send(Arm)).as(id)).provide(layers)
        }
        fired <- ZIO.scoped {
          for
            s1 <- startNode("node-a", events, timeouts)
            s2 <- startNode("node-b", events, timeouts)
            s3 <- startNode("node-c", events, timeouts)
            _  <- ZIO
              .foreach(ids) { id =>
                FSMRuntime
                  .readState(id, machine, Idle)
                  .repeatUntil(_.contains(Done))
                  .provide(ZLayer.succeed(events))
              }
              .timeoutFail(new RuntimeException("cluster did not deliver every timeout"))(15.seconds)
            // send appends before timeoutsFired is incremented, so the log can show Done first.
            fired <- (s1.metrics <*> s2.metrics <*> s3.metrics)
              .map { case (a, b, c) => a.timeoutsFired + b.timeoutsFired + c.timeoutsFired }
              .repeatUntil(_ >= n)
              .timeoutFail(new RuntimeException(s"sweepers recorded fewer than $n fires"))(15.seconds)
          yield fired
        }
        states <- ZIO.foreach(ids) { id =>
          FSMRuntime.readState(id, machine, Idle).map(id -> _).provide(ZLayer.succeed(events))
        }
        ticks <- ZIO.foreach(ids) { id =>
          events.loadEvents(id).runCollect.map(ev => id -> ev.count(_.event == Tick))
        }
        leftover <- ZIO.foreach(ids)(id => timeouts.get(id, "Tick"))
      yield assertTrue(
        states.forall(_._2.contains(Done)),
        ticks.forall(_._2 == 1),
        fired == n,
        leftover.forall(_.isEmpty),
      )
      end for
    },
    test("killing the first sweeper still delivers") {
      val id = uniqueId("failover")
      for
        timeouts <- ZIO.service[TimeoutStore[String]]
        events   <- ZIO.service[EventStore[String, ClusterState, ClusterEvent]]
        layers = runtimeLayers(events, timeouts)
        _ <- ZIO.scoped(FSMRuntime(id, machine, Idle).flatMap(_.send(Arm))).provide(layers)
        _ <- ZIO.scoped {
          for
            doomed <- startNode("doomed", events, timeouts)
            _      <- doomed.stop
            _      <- startNode("survivor", events, timeouts)
            _      <- FSMRuntime
              .readState(id, machine, Idle)
              .repeatUntil(_.contains(Done))
              .provide(ZLayer.succeed(events))
              .timeoutFail(new RuntimeException("survivor did not deliver"))(15.seconds)
          yield ()
        }
        state <- FSMRuntime.readState(id, machine, Idle).provide(ZLayer.succeed(events))
      yield assertTrue(state.contains(Done))
      end for
    },
  ).provideShared(stores) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(60.seconds)
end PostgresTimeoutClusterSpec
