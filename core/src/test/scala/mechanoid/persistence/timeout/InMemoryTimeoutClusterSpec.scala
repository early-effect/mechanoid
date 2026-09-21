package mechanoid.persistence.timeout

import zio.*
import zio.test.*
import mechanoid.core.{Finite, SequenceConflictError}
import mechanoid.machine.{Aspect, Machine, assembly, stay, via}
import mechanoid.persistence.{EventStore, FSMSnapshot}
import mechanoid.runtime.{FSMRuntime, InstanceMailbox}
import mechanoid.runtime.locking.LockingStrategy
import mechanoid.runtime.timeout.TimeoutStrategy
import mechanoid.stores.{InMemoryEventStore, InMemoryTimeoutStore}

/** Three independent sweeper nodes on shared in-memory stores. A machine left in Waiting is the failure. A Stay timeout
  * is appended once per armed generation.
  */
object InMemoryTimeoutClusterSpec extends ZIOSpecDefault:

  enum ClusterState derives Finite:
    case Idle, Waiting, Done

  enum ClusterEvent derives Finite:
    case Arm, Tick

  import ClusterState.*
  import ClusterEvent.*

  private val machine = Machine(
    assembly[ClusterState, ClusterEvent](
      (Idle via Arm to Waiting) @@ Aspect.timeout(50.millis, Tick),
      Waiting via Tick to Done,
    )
  )

  private def layers(
      events: EventStore[String, ClusterState, ClusterEvent],
      timeouts: TimeoutStore[String],
  ) =
    val timeoutEnv = ZLayer.succeed(timeouts)
    ZLayer.succeed(events) ++
      timeoutEnv ++
      (timeoutEnv >>> TimeoutStrategy.durable[String]) ++
      LockingStrategy.optimistic[String]

  private def open(
      events: InMemoryEventStore[String, ClusterState, ClusterEvent],
      timeouts: TimeoutStore[String],
  )(id: String) =
    FSMRuntime.existing(id, machine, Idle).provideSome[Scope](layers(events, timeouts))

  private def startNode(
      nodeId: String,
      events: InMemoryEventStore[String, ClusterState, ClusterEvent],
      timeouts: TimeoutStore[String],
  ): ZIO[Scope, mechanoid.core.MechanoidError, TimeoutSweeper] =
    TimeoutSweeper
      .make(
        TimeoutSweeperConfig()
          .withNodeId(nodeId)
          .withSweepInterval(20.millis)
          .withJitterFactor(0.0)
          .withClaimDuration(5.seconds)
          .withDelivery(TimeoutDelivery.AtLeastOnce),
        timeouts,
        open(events, timeouts),
      )
      .provideSome[Scope](InstanceMailbox.layer[String])

  def spec = suite("In-memory timeout cluster")(
    test("three sweepers deliver every expired timeout") {
      val n = 8
      for
        events   <- InMemoryEventStore.makeUnbounded[String, ClusterState, ClusterEvent]
        timeouts <- InMemoryTimeoutStore.make[String]
        env = layers(events, timeouts)
        ids <- ZIO.foreach(1 to n) { i =>
          val id = s"mem-$i"
          ZIO.scoped(FSMRuntime(id, machine, Idle).flatMap(_.send(Arm)).as(id)).provide(env)
        }
        now <- Clock.instant
        _   <- ZIO.foreachDiscard(ids) { id =>
          timeouts.get(id, "Tick").flatMap {
            case Some(row) =>
              timeouts.cancel(id, "Tick") *>
                timeouts.schedule(id, "Tick", row.stateHash, row.sequenceNr, now.minusSeconds(1))
            case None => ZIO.dieMessage(s"$id should have armed Tick")
          }
        }
        _ <- ZIO.scoped {
          for
            _ <- startNode("n1", events, timeouts)
            _ <- startNode("n2", events, timeouts)
            _ <- startNode("n3", events, timeouts)
            _ <- TestClock.adjust(200.millis)
            _ <- ZIO.yieldNow
            _ <- TestClock.adjust(200.millis)
            _ <- ZIO.yieldNow
            _ <- TestClock.adjust(200.millis)
            _ <- ZIO.yieldNow
          yield ()
        }
        states <- ZIO.foreach(ids) { id =>
          FSMRuntime.readState(id, machine, Idle).map(id -> _).provide(ZLayer.succeed(events))
        }
        leftover <- ZIO.foreach(ids)(id => timeouts.get(id, "Tick"))
      yield assertTrue(
        states.forall(_._2.contains(Done)),
        leftover.forall(_.isEmpty),
      )
      end for
    },
    test("stopping the first sweeper still delivers") {
      for
        events   <- InMemoryEventStore.makeUnbounded[String, ClusterState, ClusterEvent]
        timeouts <- InMemoryTimeoutStore.make[String]
        env = layers(events, timeouts)
        _   <- ZIO.scoped(FSMRuntime("fail-over", machine, Idle).flatMap(_.send(Arm))).provide(env)
        now <- Clock.instant
        _   <- timeouts.get("fail-over", "Tick").flatMap {
          case Some(row) =>
            timeouts.cancel("fail-over", "Tick") *>
              timeouts.schedule("fail-over", "Tick", row.stateHash, row.sequenceNr, now.minusSeconds(1))
          case None => ZIO.dieMessage("fail-over should have armed Tick")
        }
        _ <- ZIO.scoped {
          for
            doomed <- startNode("doomed", events, timeouts)
            _      <- doomed.stop
            _      <- startNode("survivor", events, timeouts)
            _      <- TestClock.adjust(200.millis)
            _      <- ZIO.yieldNow
            _      <- TestClock.adjust(200.millis)
            _      <- ZIO.yieldNow
          yield ()
        }
        state <- FSMRuntime.readState("fail-over", machine, Idle).provide(ZLayer.succeed(events))
      yield assertTrue(state.contains(Done))
    },
    test("two sweepers append a Stay timeout once per armed generation") {
      for
        underlying <- InMemoryEventStore.makeUnbounded[String, ClusterState, ClusterEvent]
        lock       <- Semaphore.make(1)
        events = new SequencingStore(underlying, lock)
        timeouts <- InMemoryTimeoutStore.make[String]
        env = layers(events, timeouts)
        _ <- ZIO
          .scoped(FSMRuntime("stay", stayMachine, ClusterState.Idle).flatMap(_.send(ClusterEvent.Arm)))
          .provide(
            env
          )
        now <- Clock.instant
        _   <- timeouts.get("stay", "Tick").flatMap {
          case Some(row) =>
            timeouts.cancel("stay", "Tick") *>
              timeouts.schedule("stay", "Tick", row.stateHash, row.sequenceNr, now.minusSeconds(1))
          case None => ZIO.dieMessage("stay should have armed Tick")
        }
        _ <- ZIO.scoped {
          for
            _ <- startStay("a", events, timeouts)
            _ <- startStay("b", events, timeouts)
            _ <- TestClock.adjust(200.millis)
            _ <- ZIO.yieldNow
            _ <- TestClock.adjust(200.millis)
            _ <- ZIO.yieldNow
          yield ()
        }
        logged <- events.loadEvents("stay").runCollect
        row    <- timeouts.get("stay", "Tick")
        later  <- Clock.instant
      yield assertTrue(
        logged.count(_.event == ClusterEvent.Tick) == 1,
        row.exists(_.deadline.isAfter(later)),
      )
    },
  )

  private val stayMachine = Machine(
    assembly[ClusterState, ClusterEvent](
      (Idle via Arm to Waiting) @@ Aspect.timeout(1.hour, Tick),
      Waiting via Tick to stay,
    )
  )

  private def startStay(
      nodeId: String,
      events: EventStore[String, ClusterState, ClusterEvent],
      timeouts: TimeoutStore[String],
  ): ZIO[Scope, mechanoid.core.MechanoidError, TimeoutSweeper] =
    TimeoutSweeper
      .make(
        TimeoutSweeperConfig()
          .withNodeId(nodeId)
          .withSweepInterval(20.millis)
          .withJitterFactor(0.0)
          .withClaimDuration(5.seconds),
        timeouts,
        id => FSMRuntime.existing(id, stayMachine, Idle).provideSome[Scope](layers(events, timeouts)),
      )
      .provideSome[Scope](InstanceMailbox.layer[String])

  /** In-memory append ignores `expectedSeqNr`. This wrapper fails the loser so two sweepers cannot both commit. */
  private final class SequencingStore(
      underlying: InMemoryEventStore[String, ClusterState, ClusterEvent],
      lock: Semaphore,
  ) extends EventStore[String, ClusterState, ClusterEvent]:
    override def append(instanceId: String, event: ClusterEvent, expectedSeqNr: Long) =
      lock.withPermit {
        underlying.highestSequenceNr(instanceId).flatMap { actual =>
          if actual == expectedSeqNr then underlying.append(instanceId, event, expectedSeqNr)
          else ZIO.fail(SequenceConflictError(instanceId, expectedSeqNr, actual))
        }
      }
    override def loadEvents(instanceId: String)                            = underlying.loadEvents(instanceId)
    override def loadSnapshot(instanceId: String)                          = underlying.loadSnapshot(instanceId)
    override def saveSnapshot(snapshot: FSMSnapshot[String, ClusterState]) = underlying.saveSnapshot(snapshot)
    override def highestSequenceNr(instanceId: String)                     = underlying.highestSequenceNr(instanceId)
  end SequencingStore
end InMemoryTimeoutClusterSpec
