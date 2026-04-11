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
          spec.targetTimeout.isEmpty,
        )
      },
      test("creates goto spec with timeout") {
        val spec = TransitionSpec.goto[TestState, TestEvent, TestState](
          stateHashes = Set(1),
          eventHashes = Set(2),
          stateNames = List("A"),
          eventNames = List("E1"),
          target = B,
          timeout = Some(30.seconds),
        )
        assertTrue(spec.targetTimeout == Some(30.seconds))
      },
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
          spec.targetTimeout == Some(30.seconds),
          spec.targetTimeoutConfig.isDefined,
          spec.targetTimeoutConfig.get.event == Timeout,
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
    suite("TransitionSpec.withTimeout")(
      test("adds timeout to spec") {
        val spec = TransitionSpec
          .goto[TestState, TestEvent, TestState](
            stateHashes = Set(1),
            eventHashes = Set(2),
            stateNames = List("A"),
            eventNames = List("E1"),
            target = B,
          )
          .withTimeout(60.seconds)
        assertTrue(spec.targetTimeout == Some(60.seconds))
      }
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
          spec.targetTimeout == Some(30.seconds),
          spec.targetTimeoutConfig.isDefined,
          spec.targetTimeoutConfig.get.event == Timeout,
        )
      },
      test("applies timeout aspect with non-enum (case class) event") {
        // Test the case _ branch in @@ for non-Enum events
        // Use a sealed trait with a case class child (not a scala.reflect.Enum)
        sealed trait ParamEvent derives Finite
        case class TimeoutWithData(reason: String) extends ParamEvent

        val timeoutEvent = TimeoutWithData("expired")

        // Create a spec using the timeout aspect with a non-enum event
        val spec = TransitionSpec[TestState, ParamEvent, TestState](
          stateHashes = Set(1),
          eventHashes = Set(2),
          stateNames = List("A"),
          eventNames = List("Timeout"),
          targetDesc = "-> B",
          isOverride = false,
          handler = Handler.Goto(B),
          targetTimeout = None,
        ) @@ Aspect.timeout(30.seconds, timeoutEvent)

        assertTrue(
          spec.targetTimeout == Some(30.seconds),
          spec.targetTimeoutConfig.isDefined,
          spec.targetTimeoutConfig.get.event == timeoutEvent,
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
    suite("TimeoutEventConfig")(
      test("stores event and hash") {
        val config = TimeoutEventConfig(Timeout, 123)
        assertTrue(config.event == Timeout, config.hash == 123)
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
          targetTimeout = None,
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
          targetTimeout = None,
          targetTimeoutConfig = None,
        )
        assertTrue(spec.producingEffect.isEmpty)
      },
    ),
  ) @@ TestAspect.timeout(10.seconds)

end TransitionSpecSpec
