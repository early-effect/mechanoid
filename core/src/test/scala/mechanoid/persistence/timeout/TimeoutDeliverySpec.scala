package mechanoid.persistence.timeout

import java.time.Instant
import zio.*
import zio.test.*
import mechanoid.core.{Finite, FSMState, MechanoidError, SequenceConflictError, TransitionOutcome, TransitionResult}
import mechanoid.machine.{Aspect, Machine, assembly, stay, via}
import mechanoid.persistence.{EventStore, FSMSnapshot}
import mechanoid.runtime.{FSMRuntime, InstanceMailbox}
import mechanoid.runtime.locking.LockingStrategy
import mechanoid.runtime.timeout.{FiberTimeoutStrategy, TimeoutStrategy}
import mechanoid.stores.{InMemoryEventStore, InMemoryTimeoutStore}

/** Delivery invariants for claim, reconstruct, and drop. Each test states the behavior the sweeper has to keep. */
object TimeoutDeliverySpec extends ZIOSpecDefault:

  enum Leaf derives Finite:
    case Idle, Waiting, Checking, Done

  enum Signal derives Finite:
    case Start, Ping, Tick, Finish

  import Leaf.*
  import Signal.*

  private val conflictMachine = Machine(
    assembly[Leaf, Signal](
      (Idle via Start to Waiting) @@ Aspect.timeout(1.hour, Tick),
      Waiting via Ping to stay,
      Waiting via Tick to Done,
    )
  )

  private val producingMachine = Machine(
    assembly[Leaf, Signal](
      (Idle via Start to Waiting) @@ Aspect.timeout(1.hour, Tick),
      (Waiting via Tick to Checking).producing { (_, _) =>
        ZIO.sleep(1.second).as(Finish)
      },
      Checking via Finish to Done,
    )
  )

  private val tickName: String = conflictMachine.timeoutsFor(Waiting).head.name
  private val waitingHash: Int = conflictMachine.stateEnum.caseHash(Waiting)

  private def config(node: String = "n1") =
    TimeoutSweeperConfig()
      .withSweepInterval(50.millis)
      .withJitterFactor(0.0)
      .withNodeId(node)
      .withClaimDuration(30.seconds)

  private def sweepOnce(
      makeSweeper: ZIO[Scope & InstanceMailbox[String], MechanoidError, TimeoutSweeper],
      step: Duration = 80.millis,
  ): ZIO[InstanceMailbox[String], MechanoidError, SweeperMetrics] =
    ZIO.scoped {
      for
        sweeper <- makeSweeper
        _       <- ZIO.yieldNow
        _       <- TestClock.adjust(step)
        _       <- ZIO.yieldNow
        _       <- TestClock.adjust(step)
        _       <- ZIO.yieldNow
        metrics <- sweeper.metrics
      yield metrics
    }

  private def expire(store: TimeoutStore[String], id: String, name: String): ZIO[Any, MechanoidError, Unit] =
    for
      now <- Clock.instant
      row <- store.get(id, name).flatMap {
        case Some(value) => ZIO.succeed(value)
        case None        => ZIO.dieMessage(s"$id should have armed $name")
      }
      _ <- store.cancel(id, name)
      _ <- store.schedule(id, name, row.stateHash, row.sequenceNr, now.minusSeconds(10))
    yield ()

  private def durableLayers(
      events: EventStore[String, Leaf, Signal],
      timeouts: TimeoutStore[String],
  ) =
    ZLayer.succeed(events) ++
      (ZLayer.succeed(timeouts) >>> TimeoutStrategy.durable[String]) ++
      LockingStrategy.optimistic[String]

  private def arm(
      events: EventStore[String, Leaf, Signal],
      timeouts: TimeoutStore[String],
      id: String,
      machine: Machine[Leaf, Signal],
  ) =
    ZIO
      .scoped(FSMRuntime(id, machine, Idle).flatMap(_.send(Start)))
      .provide(durableLayers(events, timeouts)) *>
      expire(timeouts, id, tickName)

  def spec = suite("Timeout delivery")(
    test("TestClock adjustment resumes a daemon sleep that another fiber is awaiting") {
      for
        done   <- Ref.make(false)
        child  <- (ZIO.sleep(1.second) *> done.set(true)).forkDaemon
        waiter <- child.await.fork
        _      <- TestClock.adjust(1.second)
        _      <- waiter.join
        flag   <- done.get
      yield assertTrue(flag)
    },
    test("a conflicting Stay releases the claim and a later sweep still delivers") {
      for
        underlying <- InMemoryEventStore.makeUnbounded[String, Leaf, Signal]
        armed      <- Ref.make(true)
        events = PingThenConflict(underlying, armed)
        timeouts <- InMemoryTimeoutStore.make[String]
        _        <- arm(events, timeouts, "conflict", conflictMachine)
        open = (id: String) =>
          FSMRuntime.existing(id, conflictMachine, Idle).provideSome[Scope](durableLayers(events, timeouts))
        _      <- sweepOnce(TimeoutSweeper.make(config(), timeouts, open))
        state  <- FSMRuntime.readState("conflict", conflictMachine, Idle).provide(ZLayer.succeed(events))
        logged <- events.loadEvents("conflict").runCollect
        left   <- timeouts.get("conflict", tickName)
      yield assertTrue(
        logged.exists(_.event == Ping),
        logged.count(_.event == Tick) == 1,
        state.contains(Done),
        left.isEmpty,
      )
    },
    test("a claim that moved the deadline into the future does not send") {
      for
        sent     <- Ref.make(List.empty[Signal])
        timeouts <- InMemoryTimeoutStore.make[String]
        now      <- Clock.instant
        _        <- timeouts.schedule("future", tickName, waitingHash, 1L, now.minusSeconds(10))
        store   = FutureOnClaim(timeouts)
        runtime = idleRuntime(sent, "future")
        _   <- sweepOnce(TimeoutSweeper.make(config(), store, _ => ZIO.succeed(runtime)))
        row <- timeouts.get("future", tickName)
        got <- sent.get
      yield assertTrue(
        got.isEmpty,
        row.isDefined,
        row.get.deadline.isAfter(now),
      )
    },
    test("complete deletes the claimed generation, not the query snapshot") {
      for
        sent     <- Ref.make(List.empty[Signal])
        timeouts <- InMemoryTimeoutStore.make[String]
        now      <- Clock.instant
        _        <- timeouts.schedule("gen", tickName, waitingHash, 1L, now.minusSeconds(10))
        store   = BumpSequenceOnClaim(timeouts, 7L)
        runtime = idleRuntime(sent, "gen")
        _   <- sweepOnce(TimeoutSweeper.make(config(), store, _ => ZIO.succeed(runtime)))
        row <- timeouts.get("gen", tickName)
        got <- sent.get
      yield assertTrue(got == List(Tick), row.isEmpty)
    },
    test("claim leaves a row alone when its deadline is still in the future") {
      for
        store  <- InMemoryTimeoutStore.make[String]
        now    <- Clock.instant
        _      <- store.schedule("early", tickName, waitingHash, 1L, now.plusSeconds(60))
        result <- store.claim("early", tickName, "n1", 30.seconds, now)
        row    <- store.get("early", tickName)
      yield assertTrue(
        result == ClaimResult.NotDue,
        row.flatMap(_.claimedBy).isEmpty,
      )
    },
    test("a release from the previous owner leaves the current claim") {
      for
        store <- InMemoryTimeoutStore.make[String]
        now   <- Clock.instant
        _     <- store.schedule("own", tickName, waitingHash, 1L, now.minusSeconds(10))
        _     <- store.claim("own", tickName, "A", 1.second, now)
        later = now.plusSeconds(10)
        stolen   <- store.claim("own", tickName, "B", 30.seconds, later)
        released <- store.release("own", tickName, "A")
        row      <- store.get("own", tickName)
      yield assertTrue(
        stolen match
          case ClaimResult.Claimed(timeout) => timeout.claimedBy.contains("B")
          case _                            => false,
        released == false,
        row.flatMap(_.claimedBy).contains("B"),
      )
    },
    test("stop waits for a producing follow-up before the runtime accepts no more sends") {
      for
        store <- InMemoryEventStore.makeUnbounded[String, Leaf, Signal]
        state <- ZIO.scoped {
          for
            runtime <- FSMRuntime("prod", producingMachine, Idle).provideSome[Scope](
              ZLayer.succeed(store),
              FiberTimeoutStrategy.layer[String],
              LockingStrategy.optimistic[String],
            )
            _         <- runtime.send(Start)
            _         <- runtime.send(Tick)
            stopFiber <- runtime.stop.fork
            _         <- TestClock.adjust(1.second)
            _         <- stopFiber.join
            current   <- runtime.currentState
          yield current
        }
      yield assertTrue(state == Done)
    },
    test("the sweeper's send-and-drop still appends the producing follow-up") {
      for
        events   <- InMemoryEventStore.makeUnbounded[String, Leaf, Signal]
        timeouts <- InMemoryTimeoutStore.make[String]
        _        <- arm(events, timeouts, "prod-sweep", producingMachine)
        open = (id: String) =>
          FSMRuntime.existing(id, producingMachine, Idle).provideSome[Scope](durableLayers(events, timeouts))
        _      <- sweepOnce(TimeoutSweeper.make(config(), timeouts, open), step = 2.seconds)
        state  <- FSMRuntime.readState("prod-sweep", producingMachine, Idle).provide(ZLayer.succeed(events))
        logged <- events.loadEvents("prod-sweep").runCollect
      yield assertTrue(
        state.contains(Done),
        logged.exists(_.event == Finish),
      )
    } @@ TestAspect.timeout(20.seconds),
    test("a timed initial leaf with no events is delivered by existing") {
      for
        events   <- InMemoryEventStore.makeUnbounded[String, Leaf, Signal]
        timeouts <- InMemoryTimeoutStore.make[String]
        _        <- ZIO
          .scoped(FSMRuntime("init", conflictMachine, Waiting))
          .provide(durableLayers(events, timeouts))
        _ <- expire(timeouts, "init", tickName)
        open = (id: String) =>
          FSMRuntime.existing(id, conflictMachine, Idle).provideSome[Scope](durableLayers(events, timeouts))
        _     <- sweepOnce(TimeoutSweeper.make(config(), timeouts, open))
        state <- FSMRuntime.readState("init", conflictMachine, Idle).provide(ZLayer.succeed(events))
      yield assertTrue(state.contains(Done))
    },
    test("a pinned sweeper does not claim another instance") {
      for
        claimed  <- Ref.make(List.empty[String])
        timeouts <- InMemoryTimeoutStore.make[String]
        now      <- Clock.instant
        _        <- timeouts.schedule("home", tickName, waitingHash, 1L, now.minusSeconds(10))
        _        <- timeouts.schedule("away", tickName, waitingHash, 1L, now.minusSeconds(10))
        store = RecordingClaims(timeouts, claimed)
        events <- Ref.make(List.empty[Signal])
        pinned = idleRuntime(events, "home")
        _    <- sweepOnce(TimeoutSweeper.pinned(config(), store, pinned))
        ids  <- claimed.get
        away <- timeouts.get("away", tickName)
        sent <- events.get
      yield assertTrue(
        !ids.contains("away"),
        ids.contains("home"),
        sent == List(Tick),
        away.flatMap(_.claimedBy).isEmpty,
      )
    },
    test("stopping a sweeper that holds the claim does not count a fire") {
      for
        events   <- InMemoryEventStore.makeUnbounded[String, Leaf, Signal]
        timeouts <- InMemoryTimeoutStore.make[String]
        _        <- arm(events, timeouts, "held", conflictMachine)
        gate     <- Promise.make[Nothing, Unit]
        claimed  <- Promise.make[Nothing, Unit]
        store = ClaimSignal(timeouts, claimed)
        sent <- Ref.make(List.empty[Signal])
        runtime = blockingRuntime(sent, "held", gate)
        doomedFired <- ZIO.scoped {
          for
            sweeper <- TimeoutSweeper.make(config("doomed"), store, _ => ZIO.succeed(runtime))
            _       <- claimed.await
            _       <- sweeper.stop
            fired   <- sweeper.metrics.map(_.timeoutsFired)
          yield fired
        }
        open = (id: String) =>
          FSMRuntime.existing(id, conflictMachine, Idle).provideSome[Scope](durableLayers(events, timeouts))
        _      <- sweepOnce(TimeoutSweeper.make(config("survivor"), timeouts, open))
        state  <- FSMRuntime.readState("held", conflictMachine, Idle).provide(ZLayer.succeed(events))
        logged <- events.loadEvents("held").runCollect
      yield assertTrue(
        doomedFired == 0,
        logged.count(_.event == Tick) == 1,
        state.contains(Done),
      )
    } @@ TestAspect.timeout(20.seconds),
    test("interrupting a blocked send releases the claim") {
      for
        timeouts <- InMemoryTimeoutStore.make[String]
        now      <- Clock.instant
        _        <- timeouts.schedule("block", tickName, waitingHash, 1L, now.minusSeconds(5))
        gate     <- Promise.make[Nothing, Unit]
        claimed  <- Promise.make[Nothing, Unit]
        store = ClaimSignal(timeouts, claimed)
        sent <- Ref.make(List.empty[Signal])
        runtime = blockingRuntime(sent, "block", gate)
        fiber <- ZIO.scoped {
          TimeoutSweeper.make(config(), store, _ => ZIO.succeed(runtime)) *> ZIO.never
        }.fork
        _   <- claimed.await
        _   <- fiber.interrupt
        row <- timeouts.get("block", tickName)
      yield assertTrue(row.flatMap(_.claimedBy).isEmpty)
    } @@ TestAspect.timeout(20.seconds),
    test("the sweeper claims inside the instance mailbox") {
      for
        inside        <- Ref.make(false)
        claimedInside <- Ref.make(false)
        timeouts      <- InMemoryTimeoutStore.make[String]
        now           <- Clock.instant
        _             <- timeouts.schedule("box", tickName, waitingHash, 1L, now.minusSeconds(5))
        box = new InstanceMailbox[String]:
          def run[R, E, A](id: String)(effect: ZIO[R, E, A]): ZIO[R, E, A] =
            inside.set(true) *> effect.ensuring(inside.set(false))
        store = new DelegatingStore(timeouts):
          override def claim(
              instanceId: String,
              name: String,
              nodeId: String,
              claimDuration: Duration,
              now: Instant,
          ) =
            inside.get.flatMap { in =>
              claimedInside.set(in) *> timeouts.claim(instanceId, name, nodeId, claimDuration, now)
            }
        sent <- Ref.make(List.empty[Signal])
        _    <- sweepOnce(TimeoutSweeper.make(config(), store, _ => ZIO.succeed(idleRuntime(sent, "box"))))
          .provideLayer(ZLayer.succeed(box))
        flag <- claimedInside.get
      yield assertTrue(flag)
    },
  ).provideLayer(InstanceMailbox.layer[String])

  private def idleRuntime(sent: Ref[List[Signal]], id: String): FSMRuntime[String, Leaf, Signal] =
    scriptedRuntime(sent, id, ZIO.unit)

  private def blockingRuntime(
      sent: Ref[List[Signal]],
      id: String,
      gate: Promise[Nothing, Unit],
  ): FSMRuntime[String, Leaf, Signal] =
    scriptedRuntime(sent, id, gate.await)

  private def scriptedRuntime(
      sent: Ref[List[Signal]],
      id: String,
      before: UIO[Unit],
  ): FSMRuntime[String, Leaf, Signal] =
    new FSMRuntime[String, Leaf, Signal]:
      override val instanceId: String             = id
      override val machine: Machine[Leaf, Signal] = conflictMachine
      override def send(event: Signal)            =
        before *> sent.update(_ :+ event).as(TransitionOutcome(TransitionResult.Stay(Waiting)))
      override def currentState         = ZIO.succeed(Waiting)
      override def state                = Clock.instant.map(now => FSMState(Waiting, Nil, Map.empty, now, now))
      override def history              = ZIO.succeed(Nil)
      override def lastSequenceNr       = ZIO.succeed(1L)
      override def saveSnapshot         = ZIO.unit
      override def stop                 = ZIO.unit
      override def stop(reason: String) = ZIO.unit
      override def delete               = ZIO.unit
      override def isRunning            = ZIO.succeed(true)
      override def timeoutConfigForState(state: Leaf) = conflictMachine.timeoutsFor(state)

  private class DelegatingStore(underlying: TimeoutStore[String]) extends TimeoutStore[String]:
    override def schedule(
        instanceId: String,
        name: String,
        stateHash: Int,
        sequenceNr: Long,
        deadline: Instant,
    )                                       = underlying.schedule(instanceId, name, stateHash, sequenceNr, deadline)
    override def cancel(instanceId: String) = underlying.cancel(instanceId)
    override def cancel(instanceId: String, name: String) = underlying.cancel(instanceId, name)
    override def queryExpired(limit: Int, now: Instant)   = underlying.queryExpired(limit, now)
    override def claim(
        instanceId: String,
        name: String,
        nodeId: String,
        claimDuration: Duration,
        now: Instant,
    ) = underlying.claim(instanceId, name, nodeId, claimDuration, now)
    override def complete(instanceId: String, name: String, sequenceNr: Long) =
      underlying.complete(instanceId, name, sequenceNr)
    override def release(instanceId: String, name: String, nodeId: String) =
      underlying.release(instanceId, name, nodeId)
    override def get(instanceId: String)               = underlying.get(instanceId)
    override def get(instanceId: String, name: String) = underlying.get(instanceId, name)
  end DelegatingStore

  /** First `Tick` append persists `Ping` instead, then fails the tick. Later appends pass through. */
  private final class PingThenConflict(
      underlying: InMemoryEventStore[String, Leaf, Signal],
      armed: Ref[Boolean],
  ) extends EventStore[String, Leaf, Signal]:
    override def append(instanceId: String, event: Signal, expectedSeqNr: Long) =
      armed.get.flatMap {
        case true if event == Tick =>
          armed.set(false) *>
            underlying.append(instanceId, Ping, expectedSeqNr) *>
            ZIO.fail(SequenceConflictError(instanceId, expectedSeqNr, expectedSeqNr + 1))
        case _ =>
          underlying.append(instanceId, event, expectedSeqNr)
      }
    override def loadEvents(instanceId: String)   = underlying.loadEvents(instanceId)
    override def loadSnapshot(instanceId: String) =
      underlying.loadSnapshot(instanceId)
    override def saveSnapshot(snapshot: FSMSnapshot[String, Leaf]) =
      underlying.saveSnapshot(snapshot)
    override def highestSequenceNr(instanceId: String) =
      underlying.highestSequenceNr(instanceId)
    override def deleteInstance(instanceId: String) =
      underlying.deleteInstance(instanceId)
  end PingThenConflict

  /** After `queryExpired`, the row a sweeper claims has a future deadline. */
  private final class FutureOnClaim(underlying: TimeoutStore[String]) extends DelegatingStore(underlying):
    override def claim(
        instanceId: String,
        name: String,
        nodeId: String,
        claimDuration: Duration,
        now: Instant,
    ) =
      underlying.get(instanceId, name).flatMap {
        case Some(row) =>
          val moved = row.copy(deadline = now.plusSeconds(3600), sequenceNr = row.sequenceNr + 3)
          underlying.cancel(instanceId, name) *>
            underlying
              .schedule(instanceId, name, moved.stateHash, moved.sequenceNr, moved.deadline)
              .as(
                ClaimResult.Claimed(
                  moved.copy(claimedBy = Some(nodeId), claimedUntil = Some(now.plusMillis(claimDuration.toMillis)))
                )
              )
        case None =>
          underlying.claim(instanceId, name, nodeId, claimDuration, now)
      }
  end FutureOnClaim

  /** The row returned from `claim` has a new sequence. The query snapshot does not. */
  private final class BumpSequenceOnClaim(underlying: TimeoutStore[String], bump: Long)
      extends DelegatingStore(underlying):
    override def claim(
        instanceId: String,
        name: String,
        nodeId: String,
        claimDuration: Duration,
        now: Instant,
    ) =
      underlying.get(instanceId, name).flatMap {
        case Some(row) =>
          underlying.cancel(instanceId, name) *>
            underlying.schedule(
              instanceId,
              name,
              row.stateHash,
              row.sequenceNr + bump,
              row.deadline,
            ) *>
            underlying.claim(instanceId, name, nodeId, claimDuration, now)
        case None =>
          underlying.claim(instanceId, name, nodeId, claimDuration, now)
      }
  end BumpSequenceOnClaim

  private final class RecordingClaims(underlying: TimeoutStore[String], claimed: Ref[List[String]])
      extends DelegatingStore(underlying):
    override def claim(
        instanceId: String,
        name: String,
        nodeId: String,
        claimDuration: Duration,
        now: Instant,
    ) =
      claimed.update(_ :+ instanceId) *>
        underlying.claim(instanceId, name, nodeId, claimDuration, now)
  end RecordingClaims

  private final class ClaimSignal(underlying: TimeoutStore[String], claimed: Promise[Nothing, Unit])
      extends DelegatingStore(underlying):
    override def claim(
        instanceId: String,
        name: String,
        nodeId: String,
        claimDuration: Duration,
        now: Instant,
    ) =
      underlying.claim(instanceId, name, nodeId, claimDuration, now).tap {
        case ClaimResult.Claimed(_) => claimed.succeed(()).unit
        case _                      => ZIO.unit
      }
  end ClaimSignal
end TimeoutDeliverySpec
