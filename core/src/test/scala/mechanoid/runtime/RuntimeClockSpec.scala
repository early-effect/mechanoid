package mechanoid.runtime

import java.time.Instant
import mechanoid.*
import mechanoid.runtime.timeout.FiberTimeoutStrategy
import zio.*
import zio.test.*

object RuntimeClockSpec extends ZIOSpecDefault:

  enum St derives Finite:
    case Idle, Running

  enum Ev derives Finite:
    case Go

  import St.*, Ev.*

  val machine: Machine[St, Ev] = Machine(assembly[St, Ev](Idle via Go to Running))

  private val t0 = Instant.parse("2026-01-01T00:00:00Z")
  private val t1 = t0.plusSeconds(60)
  private val t2 = t0.plusSeconds(120)

  private val layers =
    InMemoryEventStore.unboundedLayer[String, St, Ev] ++
      FiberTimeoutStrategy.layer[String] ++
      LockingStrategy.optimistic[String]

  def spec = suite("runtime reads time from Clock")(
    test("a started instance and its goto are stamped from Clock"):
      ZIO.scoped:
        for
          _       <- TestClock.setTime(t0)
          fsm     <- FSMRuntime("goto", machine, Idle)
          started <- fsm.state
          _       <- TestClock.setTime(t1)
          _       <- fsm.send(Go)
          moved   <- fsm.state
        yield assertTrue(
          started.startedAt == t0,
          started.lastTransitionAt == t0,
          moved.lastTransitionAt == t1,
          moved.startedAt == t0,
        )
    ,
    test("a snapshot is stamped from Clock"):
      ZIO.scoped:
        for
          _     <- TestClock.setTime(t0)
          fsm   <- FSMRuntime("snap", machine, Idle)
          _     <- fsm.send(Go)
          _     <- TestClock.setTime(t2)
          _     <- fsm.saveSnapshot
          store <- ZIO.service[EventStore[String, St, Ev]]
          snap  <- store.loadSnapshot("snap")
        yield assertTrue(snap.map(_.timestamp).contains(t2)),
  ).provideLayer(layers)
end RuntimeClockSpec
