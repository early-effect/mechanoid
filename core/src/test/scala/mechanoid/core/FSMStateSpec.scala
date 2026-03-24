package mechanoid.core

import zio.test.*
import java.time.Instant

object FSMStateSpec extends ZIOSpecDefault:

  enum S:
    case A, B, C

  import S.*

  def spec = suite("FSMState")(
    test("initial creates correct defaults") {
      val state = FSMState.initial(A)
      assertTrue(
        state.current == A,
        state.history.isEmpty,
        state.stateData.isEmpty,
        state.previousState.isEmpty,
        state.transitionCount == 0,
      )
    },
    test("transitionTo pushes old state to history") {
      val now   = Instant.now()
      val state = FSMState.initial(A).transitionTo(B, now)
      assertTrue(
        state.current == B,
        state.history == List(A),
        state.previousState.contains(A),
        state.transitionCount == 1,
        state.lastTransitionAt == now,
      )
    },
    test("multiple transitions build history in order") {
      val t1    = Instant.now()
      val t2    = t1.plusSeconds(1)
      val state = FSMState.initial(A).transitionTo(B, t1).transitionTo(C, t2)
      assertTrue(
        state.current == C,
        state.history == List(B, A),
        state.previousState.contains(B),
        state.transitionCount == 2,
      )
    },
    test("withData and getData round-trip") {
      val state = FSMState.initial(A).withData("saved", B)
      assertTrue(
        state.getData("saved").contains(B),
        state.getData("missing").isEmpty,
      )
    },
  )
end FSMStateSpec
