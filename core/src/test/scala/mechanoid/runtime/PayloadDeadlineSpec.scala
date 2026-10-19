package mechanoid.runtime

import java.time.Instant
import mechanoid.*
import mechanoid.runtime.timeout.{DurableTimeoutStrategy, FiberTimeoutStrategy, TimeoutStrategy}
import zio.*
import zio.test.*

/** A stay keeps After and At clocks, and moves a FromPayload timer when the payload instant changes. */
object PayloadDeadlineSpec extends ZIOSpecDefault:

  enum Job derives Finite:
    case Boot
    case Live(due: Long, note: String)
    case Done

  enum Act derives Finite:
    case Start
    case Edit(due: Long)
    case Watch
    case Pulse

  private val epoch: Instant = Instant.parse("2026-01-01T00:00:00Z")

  private def at(seconds: Long): Instant = epoch.plusSeconds(seconds)

  private val near: Long = 5L

  private val later: Long = 100L

  private val placeholder: Long = 1000000L

  private def dueOf(state: Job): Instant =
    state match
      case Job.Live(due, _) => Instant.ofEpochSecond(due)
      case Job.Boot         => at(placeholder)
      case Job.Done         => at(placeholder)

  private val watchDue = Aspect.timeout(Act.Watch)(dueOf)
  private val pulseDue = Aspect.timeout(Act.Pulse)(1.hour)

  private val machine: Machine[Job, Act] = Machine(
    assembly[Job, Act](
      (state[Job.Boot.type] via Act.Start).to[Job.Live]((_, _) => Job.Live(0L, "")) @@ watchDue @@ pulseDue,
      (state[Job.Live] via event[Act.Edit]).to(stay)((s, e) => s.copy(due = e.due)),
      (state[Job.Live] via Act.Watch).to(stay)((s, _) => s.copy(note = "fired")),
    )
  )

  /** Watch leaves the leaf, so a fire at the deadline does not re-arm and fire again. */
  private val firing: Machine[Job, Act] = Machine(
    assembly[Job, Act](
      (state[Job.Boot.type] via Act.Start).to[Job.Live]((_, _) => Job.Live(0L, "")) @@ watchDue @@ pulseDue,
      (state[Job.Live] via event[Act.Edit]).to(stay)((s, e) => s.copy(due = e.due)),
      (state[Job.Live] via Act.Watch).to(Job.Done),
    )
  )

  final case class Armed(deadline: Instant, schedules: Int, cancels: Int)

  final class RecordingTimeouts(rows: Ref[Map[String, Armed]]) extends TimeoutStrategy[String]:
    def get(name: String): UIO[Option[Armed]] = rows.get.map(_.get(name))

    def schedule(
        instanceId: String,
        name: String,
        stateHash: Int,
        sequenceNr: Long,
        deadline: Instant,
        onTimeout: UIO[Unit],
    ): UIO[Unit] =
      rows.update { armed =>
        val prev = armed.getOrElse(name, Armed(deadline, 0, 0))
        armed.updated(name, prev.copy(deadline = deadline, schedules = prev.schedules + 1))
      }

    def cancel(instanceId: String): UIO[Unit] =
      rows.update(_.map((name, armed) => name -> armed.copy(cancels = armed.cancels + 1)))

    def cancel(instanceId: String, name: String): UIO[Unit] =
      rows.update { armed =>
        armed.get(name) match
          case Some(row) => armed.updated(name, row.copy(cancels = row.cancels + 1))
          case None      => armed
      }

    def retain(instanceId: String, keep: Set[String]): UIO[Unit] =
      rows.get.flatMap { armed =>
        ZIO.foreachDiscard(armed.keySet.diff(keep))(cancel(instanceId, _))
      }

    def purge(instanceId: String): ZIO[Any, MechanoidError, Unit] =
      cancel(instanceId)
  end RecordingTimeouts

  private def recording: UIO[RecordingTimeouts] =
    Ref.make(Map.empty[String, Armed]).map(RecordingTimeouts(_))

  private def open(
      id: String,
      timeouts: TimeoutStrategy[String],
      initial: Job,
      definition: Machine[Job, Act] = machine,
  ): ZIO[Scope, MechanoidError, FSMRuntime[String, Job, Act]] =
    InMemoryEventStore.makeUnbounded[String, Job, Act].flatMap { store =>
      FSMRuntime(id, definition, initial).provideSome[Scope](
        ZLayer.succeed(store) ++
          ZLayer.succeed(timeouts) ++
          LockingStrategy.optimistic[String]
      )
    }

  def spec = suite("payload deadline on stay")(
    suite("fiber")(
      test("a stay that moves the deadline earlier fires at the new instant") {
        ZIO.scoped(
          for
            _        <- TestClock.setTime(epoch)
            strategy <- FiberTimeoutStrategy.make[String]
            fsm      <- open("early", strategy, Job.Live(at(later).getEpochSecond, ""), firing)
            _        <- fsm.send(Act.Edit(at(near).getEpochSecond))
            _        <- TestClock.adjust(5.seconds)
            _        <- ZIO.yieldNow
            s        <- fsm.currentState
          yield assertTrue(s == Job.Done)
        )
      },
      test("a stay from a placeholder to a near instant fires at the near instant") {
        ZIO.scoped(
          for
            _        <- TestClock.setTime(epoch)
            strategy <- FiberTimeoutStrategy.make[String]
            fsm      <- open("near", strategy, Job.Live(at(placeholder).getEpochSecond, ""), firing)
            _        <- fsm.send(Act.Edit(at(near).getEpochSecond))
            _        <- TestClock.adjust(5.seconds)
            _        <- ZIO.yieldNow
            s        <- fsm.currentState
          yield assertTrue(s == Job.Done)
        )
      },
      test("a stay out to the placeholder does not fire the old near timer") {
        ZIO.scoped(
          for
            _        <- TestClock.setTime(epoch)
            strategy <- FiberTimeoutStrategy.make[String]
            fsm      <- open("far", strategy, Job.Live(at(near).getEpochSecond, ""), firing)
            _        <- fsm.send(Act.Edit(at(placeholder).getEpochSecond))
            _        <- TestClock.adjust(5.seconds)
            _        <- ZIO.yieldNow
            s        <- fsm.currentState
          yield assertTrue(s == Job.Live(at(placeholder).getEpochSecond, ""))
        )
      },
    ) @@ TestAspect.timeout(20.seconds),
    suite("recording")(
      test("an unchanged payload instant is not cancelled or scheduled again") {
        ZIO.scoped(
          for
            timeouts <- recording
            fsm      <- open("same", timeouts, Job.Live(at(later).getEpochSecond, "keep"))
            before   <- timeouts.get("Watch")
            pulse    <- timeouts.get("Pulse")
            _        <- fsm.send(Act.Edit(at(later).getEpochSecond))
            after    <- timeouts.get("Watch")
            pulse2   <- timeouts.get("Pulse")
          yield assertTrue(
            before == after,
            pulse == pulse2,
            before.exists(_.schedules == 1),
            before.exists(_.cancels == 0),
          )
        )
      },
      test("an After timer is untouched when a payload deadline moves") {
        ZIO.scoped(
          for
            timeouts <- recording
            fsm      <- open("after", timeouts, Job.Live(at(later).getEpochSecond, ""))
            before   <- timeouts.get("Pulse")
            _        <- fsm.send(Act.Edit(at(near).getEpochSecond))
            after    <- timeouts.get("Pulse")
            watch    <- timeouts.get("Watch")
          yield assertTrue(
            before == after,
            watch.exists(_.deadline == at(near)),
            watch.exists(_.cancels == 1),
            watch.exists(_.schedules == 2),
          )
        )
      },
      test("the fired timer re-arms from the new payload") {
        ZIO.scoped(
          for
            timeouts <- recording
            fsm      <- open("fired", timeouts, Job.Live(at(later).getEpochSecond, ""))
            _        <- fsm.send(Act.Watch)
            watch    <- timeouts.get("Watch")
            s        <- fsm.currentState
          yield assertTrue(
            s == Job.Live(at(later).getEpochSecond, "fired"),
            watch.exists(_.deadline == at(later)),
            watch.exists(_.cancels == 1),
            watch.exists(_.schedules == 2),
          )
        )
      },
    ),
    test("a stay does not push history, move lastTransitionAt, or run entry and exit") {
      ZIO.scoped(
        for
          entered <- Ref.make(0)
          exited  <- Ref.make(0)
          live    = Job.Live(0L, "")
          tracked = Machine(
            assembly[Job, Act](
              (state[Job.Boot.type] via Act.Start).to[Job.Live]((_, _) => Job.Live(at(later).getEpochSecond, "")) @@
                watchDue @@ pulseDue,
              (state[Job.Live] via event[Act.Edit]).to(stay)((s, e) => s.copy(due = e.due)),
            ).onEnter(live)((_, _) => entered.update(_ + 1))
              .onExit(live)((_, _) => exited.update(_ + 1))
          )
          fsm <- InMemoryEventStore.makeUnbounded[String, Job, Act].flatMap { store =>
            FSMRuntime("life", tracked, Job.Boot).provideSome[Scope](
              ZLayer.succeed(store) ++
                FiberTimeoutStrategy.layer[String] ++
                LockingStrategy.optimistic[String]
            )
          }
          _      <- fsm.send(Act.Start)
          before <- fsm.state
          _      <- fsm.send(Act.Edit(at(near).getEpochSecond))
          after  <- fsm.state
          ins    <- entered.get
          outs   <- exited.get
        yield assertTrue(
          before.history == after.history,
          before.lastTransitionAt == after.lastTransitionAt,
          after.history == List(Job.Boot),
          ins == 1,
          outs == 0,
        )
      )
    },
    test("the stored row and a reconstructed runtime keep the new deadline") {
      ZIO.scoped(
        for
          events   <- InMemoryEventStore.makeUnbounded[String, Job, Act]
          timeouts <- InMemoryTimeoutStore.make[String]
          durable = DurableTimeoutStrategy.make[String](timeouts)
          fsm <- FSMRuntime("row", machine, Job.Live(at(later).getEpochSecond, "")).provideSome[Scope](
            ZLayer.succeed(events) ++
              ZLayer.succeed(durable) ++
              LockingStrategy.optimistic[String]
          )
          _      <- fsm.send(Act.Edit(at(near).getEpochSecond))
          stored <- timeouts.get("row", "Watch")
          _      <- fsm.stop
          _      <- FSMRuntime
            .existing("row", machine, Job.Boot)
            .provideSome[Scope](
              ZLayer.succeed(events) ++ ZLayer.succeed(durable)
            )
          kept <- timeouts.get("row", "Watch")
        yield assertTrue(
          stored.map(_.deadline) == Some(at(near)),
          kept.map(_.deadline) == Some(at(near)),
        )
      )
    },
    test("every pending payload timer matches the current payload") {
      check(Gen.long(1L, 50000L), Gen.listOfBounded(0, 7)(Gen.long(1L, 50000L))) { (first, rest) =>
        ZIO.scoped(
          for
            timeouts <- recording
            fsm      <- open("law", timeouts, Job.Live(at(first).getEpochSecond, ""))
            pulse0   <- timeouts.get("Pulse")
            _        <- ZIO.foreachDiscard(rest) { due =>
              fsm.send(Act.Edit(at(due).getEpochSecond))
            }
            watch  <- timeouts.get("Watch")
            pulse1 <- timeouts.get("Pulse")
            last = rest.lastOption.getOrElse(first)
          yield assertTrue(
            watch.map(_.deadline) == Some(at(last)),
            pulse0 == pulse1,
          )
        )
      }
    },
  )
end PayloadDeadlineSpec
