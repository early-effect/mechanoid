package mechanoid.machine

import zio.*
import zio.test.*
import mechanoid.core.Finite

object TransitionSpecSpec extends ZIOSpecDefault:

  enum TestState derives Finite:
    case A, B, C

  enum TestEvent derives Finite:
    case E1, E2, Timeout

  import TestState.*
  import TestEvent.*

  def spec = suite("TransitionSpecSpec")(
    suite("TransitionSpec.goto")(
      test("creates goto spec with correct values") {
        val spec = TransitionSpec.goto[TestState, TestEvent, TestState](
          stateHashes = Set(1),
          eventHashes = Set(2),
          stateNames = List("A"),
          eventNames = List("E1"),
          target = B,
        )
        assertTrue(
          spec.stateHashes == Set(1),
          spec.eventHashes == Set(2),
          spec.stateNames == List("A"),
          spec.eventNames == List("E1"),
          spec.targetDesc == "-> B",
          !spec.isOverride,
          spec.targetTimeouts.isEmpty,
        )
      }
    ),
    suite("TransitionSpec.gotoTimed")(
      test("creates timed goto spec") {
        val timedTarget = TimedTarget(B, 30.seconds, Timeout)
        val spec        = TransitionSpec.gotoTimed[TestState, TestEvent, TestState, TestEvent](
          stateHashes = Set(1),
          eventHashes = Set(2),
          stateNames = List("A"),
          eventNames = List("E1"),
          target = timedTarget,
        )
        assertTrue(
          spec.targetTimeouts.size == 1,
          spec.targetTimeouts.head.event == Timeout,
          spec.targetTimeouts.head.deadline == TimeoutDeadline.After(30.seconds),
        )
      }
    ),
    suite("TransitionSpec.computeGoto")(
      test("stores ComputeGoto handler and reducer") {
        val spec = TransitionSpec.computeGoto[TestState, TestEvent, TestState](
          stateHashes = Set(1),
          eventHashes = Set(2),
          stateNames = List("A"),
          eventNames = List("E1"),
          leafHash = 99,
          leafName = "B",
          reducer = PayloadReducer.pure((_, _) => B),
        )
        assertTrue(
          spec.targetDesc == "-> B",
          spec.handler == Handler.ComputeGoto(99, "B"),
          spec.payload.isDefined,
        )
      }
    ),
    suite("TransitionSpec.computeStay")(
      test("stores Stay handler and reducer") {
        val spec = TransitionSpec.computeStay[TestState, TestEvent](
          stateHashes = Set(1),
          eventHashes = Set(2),
          stateNames = List("A"),
          eventNames = List("E1"),
          reducer = PayloadReducer.pure((s, _) => s),
        )
        assertTrue(
          spec.targetDesc == "stay",
          spec.handler == Handler.Stay,
          spec.payload.isDefined,
        )
      }
    ),
    suite("TransitionSpec.stay")(
      test("creates stay spec") {
        val spec = TransitionSpec.stay[TestState, TestEvent](
          stateHashes = Set(1),
          eventHashes = Set(2),
          stateNames = List("A"),
          eventNames = List("E1"),
        )
        assertTrue(
          spec.targetDesc == "stay",
          spec.handler == Handler.Stay,
        )
      }
    ),
    suite("TransitionSpec.stop")(
      test("creates stop spec without reason") {
        val spec = TransitionSpec.stop[TestState, TestEvent](
          stateHashes = Set(1),
          eventHashes = Set(2),
          stateNames = List("A"),
          eventNames = List("E1"),
        )
        assertTrue(
          spec.targetDesc == "stop",
          spec.handler == Handler.Stop(None),
        )
      },
      test("creates stop spec with reason") {
        val spec = TransitionSpec.stop[TestState, TestEvent](
          stateHashes = Set(1),
          eventHashes = Set(2),
          stateNames = List("A"),
          eventNames = List("E1"),
          reason = Some("done"),
        )
        assertTrue(
          spec.targetDesc == "stop(done)",
          spec.handler == Handler.Stop(Some("done")),
        )
      },
    ),
    suite("TransitionSpec.@@")(
      test("applies overriding aspect") {
        val spec = TransitionSpec.goto[TestState, TestEvent, TestState](
          stateHashes = Set(1),
          eventHashes = Set(2),
          stateNames = List("A"),
          eventNames = List("E1"),
          target = B,
        ) @@ Aspect.overriding
        assertTrue(spec.isOverride)
      },
      test("applies timeout aspect with enum event") {
        val spec = TransitionSpec.goto[TestState, TestEvent, TestState](
          stateHashes = Set(1),
          eventHashes = Set(2),
          stateNames = List("A"),
          eventNames = List("E1"),
          target = B,
        ) @@ Aspect.timeout(30.seconds, Timeout)
        assertTrue(
          spec.targetTimeouts.size == 1,
          spec.targetTimeouts.head.event == Timeout,
          spec.targetTimeouts.head.deadline == TimeoutDeadline.After(30.seconds),
        )
      },
      test("accumulates stacked timeout aspects") {
        val spec = TransitionSpec.goto[TestState, TestEvent, TestState](
          stateHashes = Set(1),
          eventHashes = Set(2),
          stateNames = List("A"),
          eventNames = List("E1"),
          target = B,
        ) @@ Aspect.timeout(E1)(1.second) @@ Aspect.timeout(Timeout)(2.seconds)
        assertTrue(
          spec.targetTimeouts.size == 2,
          spec.targetTimeouts.map(_.event).toSet == Set(E1, Timeout),
        )
      },
      test("applies timeout aspect with non-enum (case class) event") {
        sealed trait ParamEvent derives Finite
        case class TimeoutWithData(reason: String) extends ParamEvent

        val timeoutEvent = TimeoutWithData("expired")

        val spec = TransitionSpec[TestState, ParamEvent, TestState](
          stateHashes = Set(1),
          eventHashes = Set(2),
          stateNames = List("A"),
          eventNames = List("Timeout"),
          targetDesc = "-> B",
          isOverride = false,
          handler = Handler.Goto(B),
        ) @@ Aspect.timeout(30.seconds, timeoutEvent)

        assertTrue(
          spec.targetTimeouts.size == 1,
          spec.targetTimeouts.head.event == timeoutEvent,
        )
      },
    ),
    suite("TransitionSpec.onEntry")(
      test("adds entry effect") {
        val spec = TransitionSpec
          .goto[TestState, TestEvent, TestState](
            stateHashes = Set(1),
            eventHashes = Set(2),
            stateNames = List("A"),
            eventNames = List("E1"),
            target = B,
          )
          .onEntry { (_, _) => ZIO.unit }
        assertTrue(spec.entryEffect.isDefined)
      }
    ),
    suite("Handler")(
      test("Goto has correct target") {
        val handler = Handler.Goto(B)
        assertTrue(handler == Handler.Goto(B))
      },
      test("Stay is a singleton") {
        assertTrue(Handler.Stay == Handler.Stay)
      },
      test("Stop stores reason") {
        val handler = Handler.Stop(Some("reason"))
        assertTrue(handler == Handler.Stop(Some("reason")))
      },
    ),
    suite("EntryEffect")(
      test("run executes the effect") {
        for
          ref <- Ref.make(false)
          effect = EntryEffect[TestEvent, TestState]((_, _) => ref.set(true))
          _      <- effect.run(E1, A)
          result <- ref.get
        yield assertTrue(result)
      }
    ),
    suite("ProducingEffect")(
      test("run executes and returns result") {
        val effect = ProducingEffect[TestEvent, TestState, TestEvent]((_, _) => ZIO.succeed(E2))
        for result <- effect.run(E1, A)
        yield assertTrue(result == E2)
      }
    ),
    suite("TimedTarget")(
      test("stores state, duration, and timeout event") {
        val target = TimedTarget(B, 30.seconds, Timeout)
        assertTrue(
          target.state == B,
          target.duration == 30.seconds,
          target.timeoutEvent == Timeout,
        )
      }
    ),
    suite("OrphanInfo")(
      test("description formats state and event names") {
        val info = OrphanInfo(
          stateHashes = Set(1, 2),
          eventHashes = Set(3),
          stateNames = List("A", "B"),
          eventNames = List("E1"),
        )
        assertTrue(info.description == "A,B via E1")
      }
    ),
    suite("IncludedHashInfo")(
      test("stores all fields correctly") {
        val info = IncludedHashInfo(
          stateHashes = Set(1),
          eventHashes = Set(2),
          stateNames = List("A"),
          eventNames = List("E1"),
          targetDesc = "-> B",
          isOverride = false,
        )
        assertTrue(
          info.stateHashes == Set(1),
          info.eventHashes == Set(2),
          info.targetDesc == "-> B",
          !info.isOverride,
        )
      }
    ),
    suite("TransitionSpec default parameters")(
      test("entryEffect defaults to None") {
        // Create spec without entryEffect - uses default
        val spec = TransitionSpec[TestState, TestEvent, TestState](
          stateHashes = Set(1),
          eventHashes = Set(2),
          stateNames = List("A"),
          eventNames = List("E1"),
          targetDesc = "-> B",
          isOverride = false,
          handler = Handler.Goto(B),
        )
        assertTrue(spec.entryEffect.isEmpty)
      },
      test("producingEffect defaults to None") {
        // Create spec without producingEffect - uses default
        val spec = TransitionSpec[TestState, TestEvent, TestState](
          stateHashes = Set(1),
          eventHashes = Set(2),
          stateNames = List("A"),
          eventNames = List("E1"),
          targetDesc = "-> B",
          isOverride = false,
          handler = Handler.Goto(B),
        )
        assertTrue(spec.producingEffect.isEmpty)
      },
    ),
  ) @@ TestAspect.timeout(10.seconds)

end TransitionSpecSpec
