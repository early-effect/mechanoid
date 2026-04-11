package mechanoid.machine

import zio.*
import zio.test.*
import mechanoid.core.{Finite, TransitionResult}

object MachineSpec extends ZIOSpecDefault:

  // Test state and event types
  enum TestState derives Finite:
    case A, B, C

  enum TestEvent derives Finite:
    case E1, E2, E3

  import TestState.*
  import TestEvent.*

  // Types for timeout tests - multiple distinct timeout events
  enum TimeoutState derives Finite:
    case Idle, WaitingForPayment, WaitingForShipment, Completed, TimedOut

  enum TimeoutEvent derives Finite:
    case Start, Pay, Ship, PaymentTimeout, ShipmentTimeout

  import TimeoutState.*
  import TimeoutEvent.*

  def spec = suite("MachineSpec")(
    suite("Machine.apply")(
      test("creates machine from assembly") {
        val asm = assembly[TestState, TestEvent](
          A via E1 to B,
          B via E2 to C,
        )
        val machine = Machine(asm)
        assertTrue(machine.specs.size == 2)
      },
      test("creates machine from assemblyAll") {
        val asm = assemblyAll[TestState, TestEvent]:
          A via E1 to B
          B via E2 to C
        val machine = Machine(asm)
        assertTrue(machine.specs.size == 2)
      },
    ),
    suite("Machine runtime")(
      test("transitions work correctly") {
        for
          runtime <- Machine(
            assembly[TestState, TestEvent](
              A via E1 to B,
              B via E2 to C,
            )
          ).start(A)
          _  <- runtime.send(E1)
          s1 <- runtime.currentState
          _  <- runtime.send(E2)
          s2 <- runtime.currentState
        yield assertTrue(s1 == B, s2 == C)
      },
      test("override semantics - last override wins") {
        for
          runtime <- Machine(
            assembly[TestState, TestEvent](
              A via E1 to B,
              (A via E1 to C) @@ Aspect.overriding,
            )
          ).start(A)
          _     <- runtime.send(E1)
          state <- runtime.currentState
        yield assertTrue(state == C)
      },
      test("stay keeps current state") {
        for
          runtime <- Machine(
            assembly[TestState, TestEvent](
              A via E1 to stay
            )
          ).start(A)
          _     <- runtime.send(E1)
          state <- runtime.currentState
        yield assertTrue(state == A)
      },
    ),
    suite("Multiple timeout event types")(
      test("machine can have different timeout events for different states") {
        val machine = Machine(
          assembly[TimeoutState, TimeoutEvent](
            // Normal flow - apply timeout aspect to transitions
            (Idle via Start to WaitingForPayment) @@ Aspect.timeout(30.seconds, PaymentTimeout),
            (WaitingForPayment via Pay to WaitingForShipment) @@ Aspect.timeout(60.seconds, ShipmentTimeout),
            WaitingForShipment via Ship to Completed,
            // Timeout handlers - each state has its own timeout event
            WaitingForPayment via PaymentTimeout to TimedOut,
            WaitingForShipment via ShipmentTimeout to TimedOut,
          )
        )

        // Verify the machine has timeout configurations for both states
        assertTrue(
          machine.timeouts.nonEmpty,
          machine.specs.size == 5,
        )
      },
      test("timeout events can trigger transitions") {
        for
          runtime <- Machine(
            assembly[TimeoutState, TimeoutEvent](
              (Idle via Start to WaitingForPayment) @@ Aspect.timeout(30.seconds, PaymentTimeout),
              WaitingForPayment via Pay to Completed,
              WaitingForPayment via PaymentTimeout to TimedOut,
            )
          ).start(Idle)
          // Start the flow
          _  <- runtime.send(Start)
          s1 <- runtime.currentState
          // Simulate timeout by sending the timeout event
          _  <- runtime.send(PaymentTimeout)
          s2 <- runtime.currentState
        yield assertTrue(s1 == WaitingForPayment, s2 == TimedOut)
      },
      test("normal event preempts timeout") {
        for
          runtime <- Machine(
            assembly[TimeoutState, TimeoutEvent](
              (Idle via Start to WaitingForPayment) @@ Aspect.timeout(30.seconds, PaymentTimeout),
              WaitingForPayment via Pay to Completed,
              WaitingForPayment via PaymentTimeout to TimedOut,
            )
          ).start(Idle)
          _  <- runtime.send(Start)
          s1 <- runtime.currentState
          // Pay before timeout
          _  <- runtime.send(Pay)
          s2 <- runtime.currentState
        yield assertTrue(s1 == WaitingForPayment, s2 == Completed)
      },
      test("different states respond to their respective timeout events") {
        val machine = Machine(
          assembly[TimeoutState, TimeoutEvent](
            (Idle via Start to WaitingForPayment) @@ Aspect.timeout(30.seconds, PaymentTimeout),
            (WaitingForPayment via Pay to WaitingForShipment) @@ Aspect.timeout(60.seconds, ShipmentTimeout),
            WaitingForShipment via Ship to Completed,
            WaitingForPayment via PaymentTimeout to TimedOut,
            WaitingForShipment via ShipmentTimeout to TimedOut,
          )
        )

        for
          // Test payment timeout path
          r1 <- machine.start(Idle)
          _  <- r1.send(Start)
          _  <- r1.send(PaymentTimeout)
          s1 <- r1.currentState
          // Test shipment timeout path
          r2 <- machine.start(Idle)
          _  <- r2.send(Start)
          _  <- r2.send(Pay)
          _  <- r2.send(ShipmentTimeout)
          s2 <- r2.currentState
        yield assertTrue(s1 == TimedOut, s2 == TimedOut)
        end for
      },
      test("timeout event in wrong state is rejected") {
        for
          runtime <- Machine(
            assembly[TimeoutState, TimeoutEvent](
              (Idle via Start to WaitingForPayment) @@ Aspect.timeout(30.seconds, PaymentTimeout),
              WaitingForPayment via Pay to Completed,
              WaitingForPayment via PaymentTimeout to TimedOut,
            )
          ).start(Idle)
          // Try to send PaymentTimeout while in Idle (no transition defined)
          result <- runtime.send(PaymentTimeout).either
        yield assertTrue(result.isLeft)
      },
    ),
    suite("State entry/exit effects via Assembly")(
      test("Machine accepts assembly with onEnter") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            A via E1 to B,
            B via E2 to C,
          ).onEnter(B) { (_, _) =>
            ZIO.unit
          }
        )
        assertTrue(machine.stateEntryEffects.nonEmpty)
      },
      test("Machine accepts assembly with onExit") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            A via E1 to B,
            B via E2 to C,
          ).onExit(A) { (_, _) =>
            ZIO.unit
          }
        )
        assertTrue(machine.stateExitEffects.nonEmpty)
      },
      test("Machine accepts assembly with both onEnter and onExit") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            A via E1 to B,
            B via E2 to C,
          ).onEnter(B) { (_, _) =>
            ZIO.unit
          }.onExit(A) { (_, _) =>
            ZIO.unit
          }
        )
        assertTrue(machine.stateEntryEffects.nonEmpty, machine.stateExitEffects.nonEmpty)
      },
      test("state entry effect runs on transition") {
        for
          ref     <- Ref.make(false)
          runtime <- Machine(
            assembly[TestState, TestEvent](
              A via E1 to B,
              B via E2 to C,
            ).onEnter(B) { (_, _) =>
              ref.set(true)
            }
          ).start(A)
          _       <- runtime.send(E1)
          state   <- runtime.currentState
          entered <- ref.get
        yield assertTrue(state == B, entered)
      },
      test("state exit effect runs on transition") {
        for
          ref     <- Ref.make(false)
          runtime <- Machine(
            assembly[TestState, TestEvent](
              A via E1 to B,
              B via E2 to C,
            ).onExit(A) { (_, _) =>
              ref.set(true)
            }
          ).start(A)
          _      <- runtime.send(E1)
          state  <- runtime.currentState
          exited <- ref.get
        yield assertTrue(state == B, exited)
      },
    ),
    suite("Machine helper methods")(
      test("stateNames returns all state names") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            A via E1 to B
          )
        )
        val names = machine.stateNames.values.toSet
        assertTrue(names.contains("A"), names.contains("B"), names.contains("C"))
      },
      test("eventNames returns all event names") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            A via E1 to B
          )
        )
        val names = machine.eventNames.values.toSet
        assertTrue(names.contains("E1"), names.contains("E2"), names.contains("E3"))
      },
      test("empty creates machine with no transitions") {
        val machine = Machine.empty[TestState, TestEvent]
        assertTrue(machine.transitions.isEmpty, machine.specs.isEmpty)
      },
    ),
    suite("Stop transition")(
      test("stop terminates the FSM") {
        for
          runtime <- Machine(
            assembly[TestState, TestEvent](
              A via E1 to stop("test reason")
            )
          ).start(A)
          outcome <- runtime.send(E1)
        yield outcome.result match
          case TransitionResult.Stop(reason) => assertTrue(reason.contains("test reason"))
          case _                             => assertTrue(false)
      }
    ),
    suite("anyOf matcher")(
      test("anyOf creates specs with multiple state hashes") {
        val asm = assembly[TestState, TestEvent](
          anyOf(A, B) via E1 to C
        )
        // Verify spec has both state hashes
        assertTrue(asm.specs.head.stateHashes.size == 2)
      },
      test("anyOf machine has transitions for all matched states") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            anyOf(A, B) via E1 to C
          )
        )
        // Check the machine has transitions for A and B using stateEnum (same hasher as runtime)
        val aHash  = machine.stateEnum.caseHash(A)
        val bHash  = machine.stateEnum.caseHash(B)
        val e1Hash = machine.eventEnum.caseHash(E1)
        assertTrue(
          machine.transitions.contains((aHash, e1Hash)),
          machine.transitions.contains((bHash, e1Hash)),
        )
      },
      test("anyOf matches any of the specified states - from state A") {
        for
          runtime <- Machine(
            assembly[TestState, TestEvent](
              anyOf(A, B) via E1 to C
            )
          ).start(A)
          _ <- runtime.send(E1)
          s <- runtime.currentState
        yield assertTrue(s == C)
      },
      test("anyOf matches any of the specified states - from state B") {
        for
          runtime <- Machine(
            assembly[TestState, TestEvent](
              anyOf(A, B) via E1 to C
            )
          ).start(B)
          _ <- runtime.send(E1)
          s <- runtime.currentState
        yield assertTrue(s == C)
      },
    ),
    suite("Machine.fromSpecs edge cases")(
      test("fromSpecs handles Stay handler correctly") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            A via E1 to stay
          )
        )
        // Verify transition exists and returns Stay
        val aHash  = machine.stateEnum.caseHash(A)
        val e1Hash = machine.eventEnum.caseHash(E1)
        assertTrue(machine.transitions.contains((aHash, e1Hash)))
      },
      test("fromSpecs handles Stop handler correctly") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            A via E1 to stop("reason")
          )
        )
        val aHash  = machine.stateEnum.caseHash(A)
        val e1Hash = machine.eventEnum.caseHash(E1)
        assertTrue(machine.transitions.contains((aHash, e1Hash)))
      },
      test("fromSpecs handles timeout with Goto correctly") {
        val machine = Machine(
          assembly[TimeoutState, TimeoutEvent](
            (Idle via Start to WaitingForPayment) @@ Aspect.timeout(30.seconds, PaymentTimeout)
          )
        )
        val waitingHash = machine.stateEnum.caseHash(WaitingForPayment)
        assertTrue(
          machine.timeouts.contains(waitingHash),
          machine.timeouts(waitingHash) == 30.seconds,
          machine.timeoutEvents.contains(waitingHash),
          machine.timeoutEvents(waitingHash) == PaymentTimeout,
        )
      },
      test("fromSpecs skips timeout for Stay handler") {
        // Timeout on Stay doesn't make sense - verify it's not set
        val machine = Machine(
          assembly[TestState, TestEvent](
            (A via E1 to stay).withTimeout(30.seconds)
          )
        )
        // Timeout should not be set for Stay
        assertTrue(machine.timeouts.isEmpty)
      },
      test("fromSpecs skips timeout for Stop handler") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            (A via E1 to stop("done")).withTimeout(30.seconds)
          )
        )
        assertTrue(machine.timeouts.isEmpty)
      },
      test("fromSpecs with entry effect on transition") {
        for
          ref <- Ref.make(false)
          machine = Machine(
            assembly[TestState, TestEvent](
              (A via E1 to B).onEntry { (_, _) => ref.set(true) }
            )
          )
          runtime <- machine.start(A)
          _       <- runtime.send(E1)
          ran     <- ref.get
        yield assertTrue(ran)
      },
      test("fromSpecs with multiple states and events (anyOf)") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            anyOf(A, B) viaAnyOf anyOfEvents(E1, E2) to C
          )
        )
        // Should have 4 transitions: (A, E1), (A, E2), (B, E1), (B, E2)
        assertTrue(machine.transitions.size == 4)
      },
    ),
    suite("Machine transitionMeta")(
      test("transitionMeta tracks Goto transitions") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            A via E1 to B
          )
        )
        assertTrue(
          machine.transitionMeta.nonEmpty,
          machine.transitionMeta.head.kind.isInstanceOf[mechanoid.visualization.TransitionKind.Goto.type],
        )
      },
      test("transitionMeta tracks Stay transitions") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            A via E1 to stay
          )
        )
        assertTrue(
          machine.transitionMeta.nonEmpty,
          machine.transitionMeta.head.kind == mechanoid.visualization.TransitionKind.Stay,
        )
      },
      test("transitionMeta tracks Stop transitions with reason") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            A via E1 to stop("done")
          )
        )
        assertTrue(
          machine.transitionMeta.nonEmpty,
          machine.transitionMeta.head.kind == mechanoid.visualization.TransitionKind.Stop(Some("done")),
        )
      },
    ),
    suite("state[T] matcher")(
      test("state[T] matches parameterized state by type") {
        // States with parameters
        enum ParamState derives Finite:
          case Idle
          case Failed(reason: String)
          case Processing(data: Int)
          case Done

        enum ParamEvent derives Finite:
          case Start, Retry, Complete

        import ParamState.*
        import ParamEvent.*

        val machine = Machine(
          assembly[ParamState, ParamEvent](
            Idle via Start to Processing(0),
            state[Failed] via Retry to Idle,
            state[Processing] via Complete to Done,
          )
        )
        // Verify we have transitions for the state[T] matchers
        assertTrue(machine.specs.size == 3)
      },
      test("state[T] via event[T] combines type matchers") {
        enum ParamState derives Finite:
          case Idle
          case Failed(reason: String)
          case Done

        enum ParamEvent derives Finite:
          case Start
          case RetryWith(delay: Int)
          case Complete

        import ParamState.*
        import ParamEvent.*

        val machine = Machine(
          assembly[ParamState, ParamEvent](
            Idle via Start to Done,
            state[Failed] via event[RetryWith] to Idle,
          )
        )
        assertTrue(machine.specs.size == 2)
      },
      test("state[T] viaAnyOf matches multiple events") {
        enum ParamState derives Finite:
          case Idle
          case Failed(reason: String)
          case Done

        enum ParamEvent derives Finite:
          case E1, E2, E3

        import ParamState.*
        import ParamEvent.*

        val machine = Machine(
          assembly[ParamState, ParamEvent](
            state[Failed] viaAnyOf anyOfEvents(E1, E2) to Idle
          )
        )
        // Should have 2 transitions: (Failed, E1), (Failed, E2)
        assertTrue(machine.transitions.size == 2)
      },
      test("state[T] toString returns readable format") {
        val matcher = state[TestState]
        assertTrue(matcher.toString.contains("state["))
      },
    ),
    suite("StateMatcher methods")(
      test("StateMatcher.via with inline event") {
        enum ParamState derives Finite:
          case Failed(reason: String)
          case Done

        enum ParamEvent derives Finite:
          case Retry

        import ParamState.*
        import ParamEvent.*

        val machine = Machine(
          assembly[ParamState, ParamEvent](
            state[Failed] via Retry to Done
          )
        )
        assertTrue(machine.specs.size == 1)
      },
      test("StateMatcher.via with EventMatcher") {
        enum ParamState derives Finite:
          case Failed(reason: String)
          case Done

        enum ParamEvent derives Finite:
          case RetryWith(delay: Int)

        import ParamState.*
        import ParamEvent.*

        val machine = Machine(
          assembly[ParamState, ParamEvent](
            state[Failed] via event[RetryWith] to Done
          )
        )
        assertTrue(machine.specs.size == 1)
      },
      test("StateMatcher.viaAnyOf with multiple events") {
        enum ParamState derives Finite:
          case Failed(reason: String)
          case Done

        enum ParamEvent derives Finite:
          case E1, E2

        import ParamState.*
        import ParamEvent.*

        val machine = Machine(
          assembly[ParamState, ParamEvent](
            state[Failed] viaAnyOf anyOfEvents(E1, E2) to Done
          )
        )
        assertTrue(machine.transitions.size == 2)
      },
      test("StateMatcher.viaAll with all events matcher") {
        enum ParamState derives Finite:
          case Failed(reason: String)
          case Done

        sealed trait ParamEvent derives Finite
        case object E1 extends ParamEvent
        case object E2 extends ParamEvent

        import ParamState.*

        val machine = Machine(
          assembly[ParamState, ParamEvent](
            state[Failed] viaAll all[ParamEvent] to Done
          )
        )
        // Should match all events (E1 and E2)
        assertTrue(machine.transitions.size == 2)
      },
    ),
    suite("Transition factory methods")(
      test("Transition.goto creates a goto transition") {
        import mechanoid.core.Transition
        val transition = Transition.goto[TestState, TestEvent, TestState](B)
        for result <- transition.action(A, E1)
        yield result match
          case TransitionResult.Goto(target) => assertTrue(target == B)
          case _                             => assertTrue(false)
      },
      test("Transition.stay creates a stay transition") {
        import mechanoid.core.Transition
        val transition = Transition.stay[TestState, TestEvent]
        for result <- transition.action(A, E1)
        yield assertTrue(result == TransitionResult.Stay)
      },
    ),
    suite("AllMatcher methods")(
      test("AllMatcher.via with EventMatcher") {
        sealed trait ParentState derives Finite
        case object StateA extends ParentState
        case object StateB extends ParentState
        case object Target extends ParentState

        enum ParamEvent derives Finite:
          case EventWith(data: Int)

        import ParamEvent.*

        val machine = Machine(
          assembly[ParentState, ParamEvent](
            all[ParentState] via event[EventWith] to Target
          )
        )
        // Should have transitions for all states (StateA, StateB, Target) with EventWith
        assertTrue(machine.transitions.size == 3)
      },
      test("AllMatcher.viaAnyOf with multiple events") {
        sealed trait ParentState derives Finite
        case object StateA extends ParentState
        case object StateB extends ParentState
        case object Target extends ParentState

        enum SimpleEvent derives Finite:
          case E1, E2

        import SimpleEvent.*

        val machine = Machine(
          assembly[ParentState, SimpleEvent](
            all[ParentState] viaAnyOf anyOfEvents(E1, E2) to Target
          )
        )
        // 3 states x 2 events = 6 transitions
        assertTrue(machine.transitions.size == 6)
      },
      test("AllMatcher.viaAll with all events") {
        sealed trait ParentState derives Finite
        case object StateA extends ParentState
        case object Target extends ParentState

        sealed trait ParentEvent derives Finite
        case object EventA extends ParentEvent
        case object EventB extends ParentEvent

        val machine = Machine(
          assembly[ParentState, ParentEvent](
            all[ParentState] viaAll all[ParentEvent] to Target
          )
        )
        // 2 states x 2 events = 4 transitions
        assertTrue(machine.transitions.size == 4)
      },
    ),
    suite("AnyOfMatcher methods")(
      test("AnyOfMatcher.via with EventMatcher") {
        enum ParamState derives Finite:
          case A, B, C

        enum ParamEvent derives Finite:
          case EventWith(data: Int)

        import ParamState.*
        import ParamEvent.*

        val machine = Machine(
          assembly[ParamState, ParamEvent](
            anyOf(A, B) via event[EventWith] to C
          )
        )
        // 2 states x 1 event = 2 transitions
        assertTrue(machine.transitions.size == 2)
      }
    ),
    suite("EventMatcher")(
      test("EventMatcher.toString returns readable format") {
        enum ParamEvent derives Finite:
          case EventWith(data: Int)

        val matcher = event[ParamEvent.EventWith]
        assertTrue(matcher.toString.contains("event["))
      }
    ),
    suite("Machine.fromSpecs default parameters")(
      test("fromSpecs uses default empty stateEntryEffects") {
        // Call fromSpecs without specifying stateEntryEffects - uses default Map.empty
        val specs = List(
          TransitionSpec.goto[TestState, TestEvent, TestState](
            stateHashes = Set(summon[Finite[TestState]].caseHash(A)),
            eventHashes = Set(summon[Finite[TestEvent]].caseHash(E1)),
            stateNames = List("A"),
            eventNames = List("E1"),
            target = B,
          )
        )
        val machine = Machine.fromSpecs[TestState, TestEvent](specs)
        assertTrue(machine.stateEntryEffects.isEmpty)
      },
      test("fromSpecs uses default empty stateExitEffects") {
        // Call fromSpecs without specifying stateExitEffects - uses default Map.empty
        val specs = List(
          TransitionSpec.goto[TestState, TestEvent, TestState](
            stateHashes = Set(summon[Finite[TestState]].caseHash(A)),
            eventHashes = Set(summon[Finite[TestEvent]].caseHash(E1)),
            stateNames = List("A"),
            eventNames = List("E1"),
            target = B,
          )
        )
        val machine = Machine.fromSpecs[TestState, TestEvent](specs)
        assertTrue(machine.stateExitEffects.isEmpty)
      },
    ),
  ).provideLayer(ZLayer.succeed(zio.Scope.global)) @@ TestAspect.sequential

end MachineSpec
