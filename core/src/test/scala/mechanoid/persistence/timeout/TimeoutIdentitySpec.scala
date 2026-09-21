package mechanoid.persistence.timeout

import zio.*
import zio.test.*
import mechanoid.core.{
  Finite,
  InstanceNotFoundError,
  InvalidTransitionError,
  MechanoidError,
  PersistenceError,
  SequenceConflictError,
  FSMState,
  TransitionOutcome,
  TransitionResult,
}
import mechanoid.machine.{Aspect, Machine, TimeoutDeadline, TimeoutSpec, assembly, via}
import mechanoid.runtime.{FSMRuntime, InstanceMailbox}
import mechanoid.runtime.locking.LockingStrategy
import mechanoid.runtime.timeout.TimeoutStrategy
import mechanoid.stores.{InMemoryEventStore, InMemoryTimeoutStore}

object TimeoutIdentitySpec extends ZIOSpecDefault:

  enum TestState derives Finite:
    case Initial, Waiting, Processing

  enum TestEvent derives Finite:
    case Start, TimeoutFired, Process

  import TestState.*
  import TestEvent.*

  private val machine: Machine[TestState, TestEvent] = Machine(
    assembly[TestState, TestEvent](
      (Initial via Start to Waiting) @@ Aspect.timeout(10.seconds, TimeoutFired),
      Waiting via TimeoutFired to Processing,
      Waiting via Process to Processing,
    )
  )

  private val waitingHash: Int = machine.stateEnum.caseHash(Waiting)

  private def mockRuntime(
      events: Ref[List[(String, TestEvent)]],
      id: String,
      leaf: TestState = Waiting,
      fail: Option[MechanoidError] = None,
  ): FSMRuntime[String, TestState, TestEvent] =
    new FSMRuntime[String, TestState, TestEvent]:
      override val instanceId: String                     = id
      override val machine: Machine[TestState, TestEvent] = TimeoutIdentitySpec.machine
      override def send(event: TestEvent)                 =
        events.update(_ :+ (id, event)) *>
          fail.fold[ZIO[Any, MechanoidError, TransitionOutcome[TestState]]](
            ZIO.succeed(TransitionOutcome(TransitionResult.Stay(leaf)))
          )(ZIO.fail(_))
      override def currentState = ZIO.succeed(leaf)
      override def state        =
        Clock.instant.map(now => FSMState(leaf, Nil, Map.empty, now, now))
      override def history                             = ZIO.succeed(Nil)
      override def lastSequenceNr                      = ZIO.succeed(0L)
      override def saveSnapshot                        = ZIO.unit
      override def stop                                = ZIO.unit
      override def stop(reason: String)                = ZIO.unit
      override def isRunning                           = ZIO.succeed(true)
      override def timeoutConfigForState(s: TestState) =
        if s == Waiting then Chunk(TimeoutSpec(TimeoutFired, "TimeoutFired", TimeoutDeadline.After(10.seconds)))
        else Chunk.empty

  private def config(node: String = "n1") =
    TimeoutSweeperConfig()
      .withSweepInterval(Duration.fromMillis(50))
      .withJitterFactor(0.0)
      .withNodeId(node)

  private def sweepOnce(
      makeSweeper: ZIO[Scope & InstanceMailbox[String], MechanoidError, TimeoutSweeper]
  ): ZIO[InstanceMailbox[String], MechanoidError, SweeperMetrics] =
    ZIO.scoped {
      for
        sweeper <- makeSweeper
        _       <- ZIO.yieldNow
        _       <- TestClock.adjust(Duration.fromMillis(80))
        _       <- ZIO.yieldNow
        _       <- TestClock.adjust(Duration.fromMillis(80))
        _       <- ZIO.yieldNow
        m       <- sweeper.metrics
      yield m
    }

  def spec = suite("Timeout identity")(
    test("opens the claimed instance id, not a pinned neighbour") {
      for
        store  <- InMemoryTimeoutStore.make[String]
        opened <- Ref.make(List.empty[String])
        events <- Ref.make(List.empty[(String, TestEvent)])
        now    <- Clock.instant
        _      <- store.schedule("a", "TimeoutFired", waitingHash, 0L, now.minusSeconds(10))
        _      <- store.schedule("b", "TimeoutFired", waitingHash, 0L, now.minusSeconds(5))
        open = (id: String) => opened.update(_ :+ id).as(mockRuntime(events, id))
        _    <- sweepOnce(TimeoutSweeper.make(config(), store, open))
        ids  <- opened.get
        sent <- events.get
      yield assertTrue(
        ids.toSet == Set("a", "b"),
        sent.map(_._1).toSet == Set("a", "b"),
        sent.forall(_._2 == TimeoutFired),
      )
    },
    test("pinned sweeper releases a foreign claim so another node can still fire it") {
      for
        store  <- InMemoryTimeoutStore.make[String]
        events <- Ref.make(List.empty[(String, TestEvent)])
        pinned = mockRuntime(events, "home")
        now     <- Clock.instant
        _       <- store.schedule("home", "TimeoutFired", waitingHash, 0L, now.minusSeconds(10))
        _       <- store.schedule("away", "TimeoutFired", waitingHash, 0L, now.minusSeconds(10))
        metrics <- sweepOnce(TimeoutSweeper.pinned(config(), store, pinned))
        sent    <- events.get
        away    <- store.get("away", "TimeoutFired")
      yield assertTrue(
        sent == List(("home", TimeoutFired)),
        away.isDefined,
        away.get.claimedBy.isEmpty,
        metrics.errors == 0,
        metrics.timeoutsFired == 1,
      )
    },
    test("real runtimes: only the expired instance receives the timeout event") {
      for
        events   <- InMemoryEventStore.make[String, TestState, TestEvent]()
        timeouts <- InMemoryTimeoutStore.make[String]
        layers = ZLayer.succeed(events) ++
          (ZLayer.succeed(timeouts) >>> TimeoutStrategy.durable[String]) ++
          LockingStrategy.optimistic[String] ++
          ZLayer.succeed(timeouts)
        _ <- ZIO
          .scoped {
            FSMRuntime("live-a", machine, Initial).flatMap(_.send(Start)) *>
              FSMRuntime("live-b", machine, Initial).flatMap(_.send(Start))
          }
          .provide(layers)
        now <- Clock.instant
        _   <- timeouts.get("live-a", "TimeoutFired").flatMap {
          case Some(row) =>
            timeouts.cancel("live-a", "TimeoutFired") *>
              timeouts.schedule("live-a", "TimeoutFired", row.stateHash, row.sequenceNr, now.minusSeconds(10))
          case None => ZIO.dieMessage("live-a should have armed TimeoutFired")
        }
        open = (id: String) => FSMRuntime.existing(id, machine, Initial).provideSome[Scope](layers)
        _      <- sweepOnce(TimeoutSweeper.make(config(), timeouts, open))
        a      <- FSMRuntime.readState("live-a", machine, Initial).provide(ZLayer.succeed(events))
        b      <- FSMRuntime.readState("live-b", machine, Initial).provide(ZLayer.succeed(events))
        stillB <- timeouts.get("live-b", "TimeoutFired")
      yield assertTrue(
        a.contains(Processing),
        b.contains(Waiting),
        stillB.isDefined,
      )
    },
    test("orphan timeout for a missing instance is completed, not retried forever") {
      for
        store <- InMemoryTimeoutStore.make[String]
        now   <- Clock.instant
        _     <- store.schedule("ghost", "TimeoutFired", waitingHash, 0L, now.minusSeconds(10))
        open = (_: String) => ZIO.fail(InstanceNotFoundError("ghost"))
        metrics <- sweepOnce(TimeoutSweeper.make(config(), store, open))
        left    <- store.get("ghost")
      yield assertTrue(left.isEmpty, metrics.timeoutsSkipped >= 1, metrics.errors == 0)
    },
    test("AtLeastOnce releases an InvalidTransitionError while the leaf still declares the name") {
      for
        store  <- InMemoryTimeoutStore.make[String]
        events <- Ref.make(List.empty[(String, TestEvent)])
        now    <- Clock.instant
        _      <- store.schedule("x", "TimeoutFired", waitingHash, 0L, now.minusSeconds(10))
        runtime = mockRuntime(
          events,
          "x",
          fail = Some(InvalidTransitionError(Processing, TimeoutFired)),
        )
        metrics <- sweepOnce(TimeoutSweeper.make(config(), store, _ => ZIO.succeed(runtime)))
        left    <- store.get("x", "TimeoutFired")
      yield assertTrue(left.isDefined, left.get.claimedBy.isEmpty, metrics.errors >= 1)
    },
    test("AtLeastOnce releases a SequenceConflictError so a later sweep can deliver") {
      for
        store  <- InMemoryTimeoutStore.make[String]
        events <- Ref.make(List.empty[(String, TestEvent)])
        now    <- Clock.instant
        _      <- store.schedule("x", "TimeoutFired", waitingHash, 0L, now.minusSeconds(10))
        runtime = mockRuntime(events, "x", fail = Some(SequenceConflictError("x", 1, 2)))
        _    <- sweepOnce(TimeoutSweeper.make(config(), store, _ => ZIO.succeed(runtime)))
        left <- store.get("x", "TimeoutFired")
      yield assertTrue(left.isDefined, left.get.claimedBy.isEmpty)
    },
    test("store failure releases so a later sweep can still deliver") {
      for
        store  <- InMemoryTimeoutStore.make[String]
        events <- Ref.make(List.empty[(String, TestEvent)])
        now    <- Clock.instant
        _      <- store.schedule("x", "TimeoutFired", waitingHash, 0L, now.minusSeconds(10))
        runtime = mockRuntime(events, "x", fail = Some(PersistenceError("db down")))
        metrics <- sweepOnce(TimeoutSweeper.make(config(), store, _ => ZIO.succeed(runtime)))
        left    <- store.get("x", "TimeoutFired")
      yield assertTrue(
        left.isDefined,
        left.get.claimedBy.isEmpty,
        metrics.errors >= 1,
      )
    },
    test("UntilDelivered also releases a no-op InvalidTransitionError") {
      for
        store  <- InMemoryTimeoutStore.make[String]
        events <- Ref.make(List.empty[(String, TestEvent)])
        now    <- Clock.instant
        _      <- store.schedule("x", "TimeoutFired", waitingHash, 0L, now.minusSeconds(10))
        runtime = mockRuntime(
          events,
          "x",
          fail = Some(InvalidTransitionError(Processing, TimeoutFired)),
        )
        cfg = config().withDelivery(TimeoutDelivery.UntilDelivered)
        _    <- sweepOnce(TimeoutSweeper.make(cfg, store, _ => ZIO.succeed(runtime)))
        left <- store.get("x", "TimeoutFired")
      yield assertTrue(left.isDefined, left.get.claimedBy.isEmpty)
    },
  ).provideLayer(InstanceMailbox.layer[String])
end TimeoutIdentitySpec
