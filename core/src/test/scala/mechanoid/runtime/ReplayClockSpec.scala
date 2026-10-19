package mechanoid.runtime

import java.time.Instant
import mechanoid.*
import zio.*
import zio.test.*

/** Replay runs each action at the instant stored with that event. */
object ReplayClockSpec extends ZIOSpecDefault:

  enum St derives Finite:
    case Idle
    case Seen(at: Instant)

  enum Ev derives Finite:
    case Go, Again, Fire

  private val nanos     = Instant.parse("2026-06-01T00:00:00.123456789Z")
  private val truncated = Instant.ofEpochMilli(nanos.toEpochMilli)
  private val later     = Instant.parse("2026-07-01T00:00:00Z")

  private val machine: Machine[St, Ev] = Machine(
    assembly[St, Ev](
      (state[St.Idle.type] via Ev.Go).to[St.Seen]((_, _) => Clock.instant.map(St.Seen(_))),
      (state[St.Seen] via Ev.Again).to[St.Seen]((_, _) => Clock.instant.map(St.Seen(_))),
    )
  )

  private def dueOf(state: St): Instant =
    state match
      case St.Seen(at) => at
      case St.Idle     => Instant.EPOCH

  private val watched: Machine[St, Ev] = Machine(
    assembly[St, Ev](
      (state[St.Idle.type] via Ev.Go).to[St.Seen]((_, _) => Clock.instant.map(St.Seen(_))) @@
        Aspect.timeout(Ev.Fire)(dueOf)
    )
  )

  private def open(
      id: String,
      store: InMemoryEventStore[String, St, Ev],
      definition: Machine[St, Ev] = machine,
  ): ZIO[Scope, MechanoidError, FSMRuntime[String, St, Ev]] =
    FSMRuntime(id, definition, St.Idle).provideSome[Scope](
      ZLayer.succeed(store) ++
        FiberTimeoutStrategy.layer[String] ++
        LockingStrategy.optimistic[String]
    )

  def spec = suite("one instant per event")(
    test("a rebuild after the clock moves reproduces the live leaf") {
      ZIO.scoped {
        for
          store   <- InMemoryEventStore.makeUnbounded[String, St, Ev]
          _       <- TestClock.setTime(nanos)
          fsm     <- open("rebuild", store)
          _       <- fsm.send(Ev.Go)
          _       <- TestClock.setTime(later)
          _       <- fsm.send(Ev.Again)
          live    <- fsm.state
          _       <- fsm.stop
          _       <- TestClock.setTime(later.plusSeconds(3600))
          rebuilt <- open("rebuild", store)
          again   <- rebuilt.state
          first   <- FSMRuntime.readState("rebuild", machine, St.Idle).provide(ZLayer.succeed(store))
          _       <- TestClock.setTime(later.plusSeconds(7200))
          second  <- FSMRuntime.readState("rebuild", machine, St.Idle).provide(ZLayer.succeed(store))
        yield assertTrue(
          live.current == St.Seen(later),
          again.current == live.current,
          first == Some(live.current),
          second == first,
        )
      }
    },
    test("the action, the stored event, and lastTransitionAt share one truncated instant") {
      ZIO.scoped {
        for
          store  <- InMemoryEventStore.makeUnbounded[String, St, Ev]
          _      <- TestClock.setTime(nanos)
          fsm    <- open("one", store)
          _      <- fsm.send(Ev.Go)
          moved  <- fsm.state
          events <- store.loadEvents("one").runCollect
        yield assertTrue(
          nanos != truncated,
          moved.current == St.Seen(truncated),
          moved.lastTransitionAt == truncated,
          events.map(_.timestamp) == Chunk(truncated),
        )
      }
    },
    test("a missing timeout row is rewritten at the original payload instant") {
      ZIO.scoped {
        for
          events   <- InMemoryEventStore.makeUnbounded[String, St, Ev]
          timeouts <- InMemoryTimeoutStore.make[String]
          durable = DurableTimeoutStrategy.make[String](timeouts)
          fsm <- FSMRuntime("durable", watched, St.Idle).provideSome[Scope](
            ZLayer.succeed(events) ++
              ZLayer.succeed(durable) ++
              LockingStrategy.optimistic[String]
          )
          _       <- TestClock.setTime(nanos)
          _       <- fsm.send(Ev.Go)
          armed   <- timeouts.get("durable")
          _       <- fsm.stop
          _       <- timeouts.cancel("durable")
          gone    <- timeouts.get("durable")
          _       <- TestClock.setTime(later)
          rebuilt <- FSMRuntime("durable", watched, St.Idle).provideSome[Scope](
            ZLayer.succeed(events) ++
              ZLayer.succeed(durable) ++
              LockingStrategy.optimistic[String]
          )
          leaf <- rebuilt.state
          row  <- timeouts.get("durable")
        yield assertTrue(
          armed.map(_.deadline) == Chunk(truncated),
          gone.isEmpty,
          leaf.current == St.Seen(truncated),
          row.map(_.deadline) == Chunk(truncated),
        )
      }
    },
    test("rebuilt leaf equals the live leaf across clock jumps") {
      check(Gen.listOfBounded(1, 6)(Gen.long(1L, 5000L))) { jumps =>
        ZIO.scoped {
          for
            store <- InMemoryEventStore.makeUnbounded[String, St, Ev]
            _     <- TestClock.setTime(nanos)
            fsm   <- open("jumps", store)
            _     <- fsm.send(Ev.Go)
            _     <- ZIO.foreachDiscard(jumps) { ms =>
              TestClock.adjust(ms.millis) *> fsm.send(Ev.Again)
            }
            live    <- fsm.state
            _       <- fsm.stop
            _       <- TestClock.setTime(later)
            rebuilt <- open("jumps", store)
            again   <- rebuilt.state
          yield assertTrue(again.current == live.current)
        }
      }
    },
  )
end ReplayClockSpec
