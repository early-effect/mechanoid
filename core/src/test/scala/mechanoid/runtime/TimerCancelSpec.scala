package mechanoid.runtime

import java.time.Instant
import mechanoid.*
import mechanoid.persistence.FSMSnapshot
import mechanoid.runtime.timeout.FiberTimeoutStrategy
import zio.*
import zio.test.*

/** A timer armed while a runtime is acquired must still cancel. Acquire is uninterruptible, and `forkDaemon` inherits
  * that status.
  */
object TimerCancelSpec extends ZIOSpecDefault:

  enum St derives Finite:
    case Idle(at: Long)
    case Waiting(at: Long)
    case Done(at: Long)

  enum Ev derives Finite:
    case Go
    case Tick

  val epoch: Instant = Instant.parse("2026-01-01T00:00:00Z")

  private val tickDue = Aspect.timeout(Ev.Tick) { (s: St) =>
    s match
      case St.Waiting(at) => Instant.ofEpochSecond(at)
      case _              => epoch.plusSeconds(1000000)
  }

  val machine: Machine[St, Ev] = Machine(
    assembly[St, Ev](
      ((state[St.Idle] via Ev.Go).to[St.Waiting]((_, _) => St.Waiting(epoch.plusSeconds(5).getEpochSecond))) @@
        tickDue,
      (state[St.Waiting] via Ev.Tick).to[St.Done]((w, _) => St.Done(w.at)),
    )
  )

  private val layers =
    InMemoryEventStore.unboundedLayer[String, St, Ev] ++
      FiberTimeoutStrategy.layer[String] ++
      LockingStrategy.optimistic[String]

  private val due: Long = epoch.plusSeconds(5).getEpochSecond

  private val liveDue: Long = Instant.now().plusSeconds(3600).getEpochSecond

  val mechanism: Spec[Any, Nothing] = suite("forkDaemon inside acquire")(
    test("inherits uninterruptible and interrupt waits") {
      ZIO.scoped(
        for
          fiber  <- ZIO.acquireRelease(ZIO.sleep(1.hour).forkDaemon)(_ => ZIO.unit)
          result <- fiber.interrupt.timeout(2.seconds)
        yield assertTrue(result.isEmpty)
      )
    },
    test("interruptible sleep returns from interrupt") {
      ZIO.scoped(
        for
          fiber  <- ZIO.acquireRelease(ZIO.sleep(1.hour).interruptible.forkDaemon)(_ => ZIO.unit)
          result <- fiber.interrupt.timeout(2.seconds)
        yield assertTrue(result.exists(_.isInterrupted))
      )
    },
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(15.seconds)

  val liveLeave: Spec[EventStore[String, St, Ev] & TimeoutStrategy[String] & LockingStrategy[String], MechanoidError] =
    suite("leave a timer armed at start")(
      test("send returns on the live clock") {
        ZIO.scoped(
          for
            fsm <- FSMRuntime("live", machine, St.Waiting(liveDue))
            _   <- fsm.send(Ev.Tick)
            s   <- fsm.currentState
          yield assertTrue(s == St.Done(liveDue))
        )
      }
    ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(8.seconds)

  val clockLeave: Spec[EventStore[String, St, Ev] & TimeoutStrategy[String] & LockingStrategy[String], MechanoidError] =
    suite("leave a timer armed at start under TestClock")(
      test("send arrives at Done") {
        ZIO.scoped(
          for
            _   <- TestClock.setTime(epoch)
            fsm <- FSMRuntime("clock", machine, St.Waiting(due))
            _   <- fsm.send(Ev.Tick)
            s   <- fsm.currentState
          yield assertTrue(s == St.Done(due))
        )
      },
      test("the timer still fires when nothing cancels it") {
        ZIO.scoped(
          for
            _   <- TestClock.setTime(epoch)
            fsm <- FSMRuntime("fire", machine, St.Waiting(due))
            _   <- TestClock.adjust(5.seconds)
            _   <- ZIO.yieldNow
            s   <- fsm.currentState
          yield assertTrue(s == St.Done(due))
        )
      },
    )

  val existingLeave
      : Spec[EventStore[String, St, Ev] & TimeoutStrategy[String] & LockingStrategy[String], MechanoidError] =
    test("existing on a timed leaf then a goto returns") {
      ZIO.scoped(
        for
          _     <- TestClock.setTime(epoch)
          store <- ZIO.service[EventStore[String, St, Ev]]
          _     <- store.saveSnapshot(FSMSnapshot("reopen", St.Waiting(due), 0L, epoch))
          fsm   <- FSMRuntime.existing("reopen", machine, St.Idle(0L))
          _     <- fsm.send(Ev.Tick)
          s     <- fsm.currentState
        yield assertTrue(s == St.Done(due))
      )
    }

  def spec = suite("cancel a timer armed at start")(
    mechanism,
    liveLeave,
    clockLeave,
    existingLeave,
  ).provideLayer(layers)
end TimerCancelSpec
