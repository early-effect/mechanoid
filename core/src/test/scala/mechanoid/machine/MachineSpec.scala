package mechanoid.machine

import zio.*
import zio.test.*
import mechanoid.core.Finite

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
  ).provideLayer(ZLayer.succeed(zio.Scope.global)) @@ TestAspect.sequential

end MachineSpec
