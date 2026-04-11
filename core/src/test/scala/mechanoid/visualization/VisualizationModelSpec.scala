package mechanoid.visualization

import zio.test.*
import java.time.Instant

object VisualizationModelSpec extends ZIOSpecDefault:

  // Simple test types
  enum TestState:
    case A, B, C

  enum TestEvent:
    case E1, E2

  import TestState.*
  import TestEvent.*

  def spec = suite("VisualizationModelSpec")(
    suite("TraceStep")(
      test("isSelfTransition returns true when from == to") {
        val step = TraceStep(1, A, A, E1, Instant.now(), false)
        assertTrue(step.isSelfTransition)
      },
      test("isSelfTransition returns false when from != to") {
        val step = TraceStep(1, A, B, E1, Instant.now(), false)
        assertTrue(!step.isSelfTransition)
      },
    ),
    suite("ExecutionTrace")(
      test("isEmpty returns true for empty trace") {
        val trace = ExecutionTrace.empty[TestState, TestEvent]("test-1", A)
        assertTrue(trace.isEmpty)
      },
      test("isEmpty returns false for non-empty trace") {
        val trace = ExecutionTrace(
          "test-1",
          A,
          B,
          List(TraceStep(1, A, B, E1, Instant.now(), false)),
        )
        assertTrue(!trace.isEmpty)
      },
      test("stepCount returns correct count") {
        val trace = ExecutionTrace(
          "test-1",
          A,
          C,
          List(
            TraceStep(1, A, B, E1, Instant.now(), false),
            TraceStep(2, B, C, E2, Instant.now(), false),
          ),
        )
        assertTrue(trace.stepCount == 2)
      },
      test("isTerminal returns false for empty trace") {
        val trace = ExecutionTrace.empty[TestState, TestEvent]("test-1", A)
        assertTrue(!trace.isTerminal)
      },
      test("isTerminal returns true when last step is timeout self-transition") {
        val trace = ExecutionTrace(
          "test-1",
          A,
          A,
          List(TraceStep(1, A, A, E1, Instant.now(), isTimeout = true)),
        )
        assertTrue(trace.isTerminal)
      },
      test("isTerminal returns false when last step is timeout but from != to") {
        val trace = ExecutionTrace(
          "test-1",
          A,
          B,
          List(TraceStep(1, A, B, E1, Instant.now(), isTimeout = true)),
        )
        assertTrue(!trace.isTerminal)
      },
      test("isTerminal returns false when last step is not timeout") {
        val trace = ExecutionTrace(
          "test-1",
          A,
          A,
          List(TraceStep(1, A, A, E1, Instant.now(), isTimeout = false)),
        )
        assertTrue(!trace.isTerminal)
      },
      test("visitedStates includes all states from steps plus initial") {
        val trace = ExecutionTrace(
          "test-1",
          A,
          C,
          List(
            TraceStep(1, A, B, E1, Instant.now(), false),
            TraceStep(2, B, C, E2, Instant.now(), false),
          ),
        )
        assertTrue(trace.visitedStates == Set(A, B, C))
      },
      test("visitedStates includes initial state even with no steps") {
        val trace = ExecutionTrace.empty[TestState, TestEvent]("test-1", A)
        assertTrue(trace.visitedStates == Set(A))
      },
      test("triggeredEvents returns all unique events") {
        val trace = ExecutionTrace(
          "test-1",
          A,
          C,
          List(
            TraceStep(1, A, B, E1, Instant.now(), false),
            TraceStep(2, B, C, E2, Instant.now(), false),
            TraceStep(3, C, A, E1, Instant.now(), false),
          ),
        )
        assertTrue(trace.triggeredEvents == Set(E1, E2))
      },
      test("triggeredEvents returns empty set for empty trace") {
        val trace = ExecutionTrace.empty[TestState, TestEvent]("test-1", A)
        assertTrue(trace.triggeredEvents.isEmpty)
      },
    ),
    suite("ExecutionTrace.empty")(
      test("creates trace with correct instanceId") {
        val trace = ExecutionTrace.empty[TestState, TestEvent]("my-instance", A)
        assertTrue(trace.instanceId == "my-instance")
      },
      test("creates trace with initialState as both initial and current") {
        val trace = ExecutionTrace.empty[TestState, TestEvent]("test-1", B)
        assertTrue(trace.initialState == B, trace.currentState == B)
      },
      test("creates trace with empty steps") {
        val trace = ExecutionTrace.empty[TestState, TestEvent]("test-1", A)
        assertTrue(trace.steps.isEmpty)
      },
    ),
  )
end VisualizationModelSpec
