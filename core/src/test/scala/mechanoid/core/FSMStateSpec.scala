package mechanoid.core

import zio.test.*
import java.time.Instant

object FSMStateSpec extends ZIOSpecDefault:

  enum S:
    case A, B, C

  import S.*

  private val t0 = Instant.parse("2026-01-01T00:00:00Z")

  def spec = suite("FSMState")(
    test("initial creates correct defaults") {
      val state = FSMState.initial(A, t0)
      assertTrue(
        state.current == A,
        state.history.isEmpty,
        state.stateData.isEmpty,
        state.previousState.isEmpty,
        state.transitionCount == 0,
        state.startedAt == t0,
        state.lastTransitionAt == t0,
      )
    },
    test("transitionTo pushes old state to history") {
      val now   = t0.plusSeconds(1)
      val state = FSMState.initial(A, t0).transitionTo(B, now)
      assertTrue(
        state.current == B,
        state.history == List(A),
        state.previousState.contains(A),
        state.transitionCount == 1,
        state.lastTransitionAt == now,
      )
    },
    test("multiple transitions build history in order") {
      val t1    = t0.plusSeconds(1)
      val t2    = t1.plusSeconds(1)
      val state = FSMState.initial(A, t0).transitionTo(B, t1).transitionTo(C, t2)
      assertTrue(
        state.current == C,
        state.history == List(B, A),
        state.previousState.contains(B),
        state.transitionCount == 2,
      )
    },
    test("replaceCurrent keeps history and lastTransitionAt") {
      val t1    = t0.plusSeconds(1)
      val t2    = t1.plusSeconds(5)
      val state = FSMState.initial(A, t0).transitionTo(B, t1).replaceCurrent(C)
      assertTrue(
        state.current == C,
        state.history == List(A),
        state.lastTransitionAt == t1,
        state.lastTransitionAt != t2,
        state.transitionCount == 1,
      )
    },
    test("withData and getData round-trip") {
      val state = FSMState.initial(A, t0).withData("saved", B)
      assertTrue(
        state.getData("saved").contains(B),
        state.getData("missing").isEmpty,
      )
    },
    test("direct construction with defaults") {
      // Exercise the default parameter accessors by using FSMState directly
      // with only required params, letting others default
      val state = FSMState(
        current = A,
        startedAt = t0,
        lastTransitionAt = t0,
      )
      assertTrue(
        state.current == A,
        state.history.isEmpty,  // default: Nil
        state.stateData.isEmpty, // default: Map.empty
      )
    },
  )
end FSMStateSpec
