package mechanoid.visualization

import zio.*
import zio.test.*
import mechanoid.core.Finite
import mechanoid.machine.*
import java.time.Instant

object MermaidVisualizerSpec extends ZIOSpecDefault:

  enum TestState derives Finite:
    case Idle, Processing, Completed, Failed

  enum TestEvent derives Finite:
    case Start, Finish, Fail, Retry

  import TestState.*
  import TestEvent.*

  def spec = suite("MermaidVisualizerSpec")(
    suite("stateDiagram")(
      test("generates valid stateDiagram-v2 header") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            Idle via Start to Processing
          )
        )
        val result = MermaidVisualizer.stateDiagram(machine)
        assertTrue(result.startsWith("stateDiagram-v2\n"))
      },
      test("includes initial state arrow when provided") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            Idle via Start to Processing
          )
        )
        val result = MermaidVisualizer.stateDiagram(machine, initialState = Some(Idle))
        assertTrue(result.contains("[*] --> Idle"))
      },
      test("no initial state arrow when not provided") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            Idle via Start to Processing
          )
        )
        val result = MermaidVisualizer.stateDiagram(machine, initialState = None)
        assertTrue(!result.contains("[*] -->"))
      },
      test("includes Goto transitions") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            Idle via Start to Processing,
            Processing via Finish to Completed,
          )
        )
        val result = MermaidVisualizer.stateDiagram(machine)
        assertTrue(
          result.contains("Idle --> Processing: Start"),
          result.contains("Processing --> Completed: Finish"),
        )
      },
      test("handles Stay transitions as self-loops") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            Processing via Retry to stay
          )
        )
        val result = MermaidVisualizer.stateDiagram(machine)
        assertTrue(result.contains("Processing --> Processing: Retry"))
      },
      test("handles Stop transitions to terminal state") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            Processing via Fail to stop
          )
        )
        val result = MermaidVisualizer.stateDiagram(machine)
        assertTrue(result.contains("Processing --> [*]: Fail [stop]"))
      },
      test("handles Stop transitions with reason") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            Processing via Fail to stop("error occurred")
          )
        )
        val result = MermaidVisualizer.stateDiagram(machine)
        assertTrue(result.contains("Processing --> [*]: Fail [stop: error occurred]"))
      },
      test("adds timeout notes for states with timeouts") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            (Idle via Start to Processing) @@ Aspect.timeout(30.seconds, Retry)
          )
        )
        val result = MermaidVisualizer.stateDiagram(machine)
        assertTrue(
          result.contains("note right of"),
          result.contains("timeout:"),
        )
      },
    ),
    suite("sequenceDiagram")(
      test("generates valid sequenceDiagram header") {
        val trace  = ExecutionTrace.empty[TestState, TestEvent]("test-1", Idle)
        val result = MermaidVisualizer.sequenceDiagram(trace, summon[Finite[TestState]], summon[Finite[TestEvent]])
        assertTrue(result.startsWith("sequenceDiagram\n"))
      },
      test("includes participant with instanceId") {
        val trace  = ExecutionTrace.empty[TestState, TestEvent]("my-instance-123", Idle)
        val result = MermaidVisualizer.sequenceDiagram(trace, summon[Finite[TestState]], summon[Finite[TestEvent]])
        assertTrue(result.contains("participant FSM as my-instance-123"))
      },
      test("shows initial state note") {
        val trace  = ExecutionTrace.empty[TestState, TestEvent]("test-1", Processing)
        val result = MermaidVisualizer.sequenceDiagram(trace, summon[Finite[TestState]], summon[Finite[TestEvent]])
        assertTrue(result.contains("Note over FSM: Processing"))
      },
      test("shows event transitions") {
        val trace = ExecutionTrace(
          "test-1",
          Idle,
          Processing,
          List(TraceStep(1, Idle, Processing, Start, Instant.now(), false)),
        )
        val result = MermaidVisualizer.sequenceDiagram(trace, summon[Finite[TestState]], summon[Finite[TestEvent]])
        assertTrue(result.contains("FSM->>FSM: Start"))
      },
      test("marks self-transitions with (stay)") {
        val trace = ExecutionTrace(
          "test-1",
          Processing,
          Processing,
          List(TraceStep(1, Processing, Processing, Retry, Instant.now(), false)),
        )
        val result = MermaidVisualizer.sequenceDiagram(trace, summon[Finite[TestState]], summon[Finite[TestEvent]])
        assertTrue(result.contains("FSM->>FSM: Retry (stay)"))
      },
      test("shows state note after non-self transition") {
        val trace = ExecutionTrace(
          "test-1",
          Idle,
          Processing,
          List(TraceStep(1, Idle, Processing, Start, Instant.now(), false)),
        )
        val result = MermaidVisualizer.sequenceDiagram(trace, summon[Finite[TestState]], summon[Finite[TestEvent]])
        assertTrue(result.contains("Note over FSM: Processing"))
      },
      test("shows Timeout for timeout events") {
        val trace = ExecutionTrace(
          "test-1",
          Idle,
          Processing,
          List(TraceStep(1, Idle, Processing, Start, Instant.now(), isTimeout = true)),
        )
        val result = MermaidVisualizer.sequenceDiagram(trace, summon[Finite[TestState]], summon[Finite[TestEvent]])
        assertTrue(result.contains("FSM->>FSM: Timeout"))
      },
      test("shows current state marker at end") {
        val trace = ExecutionTrace(
          "test-1",
          Idle,
          Completed,
          List(
            TraceStep(1, Idle, Processing, Start, Instant.now(), false),
            TraceStep(2, Processing, Completed, Finish, Instant.now(), false),
          ),
        )
        val result = MermaidVisualizer.sequenceDiagram(trace, summon[Finite[TestState]], summon[Finite[TestEvent]])
        assertTrue(result.contains("Note over FSM: Current: Completed"))
      },
    ),
    suite("flowchart")(
      test("generates valid flowchart LR header") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            Idle via Start to Processing
          )
        )
        val result = MermaidVisualizer.flowchart(machine)
        assertTrue(result.startsWith("flowchart LR\n"))
      },
      test("defines states as double-circle nodes") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            Idle via Start to Processing
          )
        )
        val result = MermaidVisualizer.flowchart(machine)
        assertTrue(
          result.contains("Idle((Idle))"),
          result.contains("Processing((Processing))"),
        )
      },
      test("includes Goto transitions with event labels") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            Idle via Start to Processing
          )
        )
        val result = MermaidVisualizer.flowchart(machine)
        assertTrue(result.contains("Idle -->|Start| Processing"))
      },
      test("handles Stay transitions as self-loops") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            Processing via Retry to stay
          )
        )
        val result = MermaidVisualizer.flowchart(machine)
        assertTrue(result.contains("Processing -->|Retry| Processing"))
      },
      test("adds END node for Stop transitions") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            Processing via Fail to stop
          )
        )
        val result = MermaidVisualizer.flowchart(machine)
        assertTrue(
          result.contains("END([End])"),
          result.contains("Processing -->|Fail| END"),
        )
      },
      test("highlights visited states when trace provided") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            Idle via Start to Processing,
            Processing via Finish to Completed,
          )
        )
        val trace = ExecutionTrace(
          "test-1",
          Idle,
          Processing,
          List(TraceStep(1, Idle, Processing, Start, Instant.now(), false)),
        )
        val result = MermaidVisualizer.flowchart(machine, Some(trace))
        assertTrue(
          result.contains("style Idle fill:#ADD8E6"),      // visited - light blue
          result.contains("style Processing fill:#90EE90"), // current - light green
        )
      },
      test("no highlighting without trace") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            Idle via Start to Processing
          )
        )
        val result = MermaidVisualizer.flowchart(machine, None)
        assertTrue(!result.contains("style Idle fill:"))
      },
    ),
    suite("formatEventForDiagram")(
      test("formats simple case object") {
        val result = MermaidVisualizer.formatEventForDiagram(Start)
        assertTrue(result == "Start")
      },
      test("formats parameterized case class") {
        case class ParamEvent(value: String)
        val result = MermaidVisualizer.formatEventForDiagram(ParamEvent("test"))
        assertTrue(result == "ParamEvent(test)")
      },
      test("truncates long values with parentheses") {
        case class LongParamEvent(value: String)
        val longValue = "a" * 100
        val result    = MermaidVisualizer.formatEventForDiagram(LongParamEvent(longValue))
        assertTrue(result.endsWith("(...)"))
      },
      test("truncates long values without parentheses") {
        val longString = "A" * 100
        val result     = MermaidVisualizer.formatEventForDiagram(longString)
        assertTrue(result.endsWith("..."), result.length <= 60)
      },
    ),
    suite("formatStateForDiagram")(
      test("formats simple case object") {
        val result = MermaidVisualizer.formatStateForDiagram(Idle)
        assertTrue(result == "Idle")
      },
      test("formats parameterized case class") {
        case class ParamState(count: Int)
        val result = MermaidVisualizer.formatStateForDiagram(ParamState(42))
        assertTrue(result == "ParamState(42)")
      },
    ),
    suite("escapeMermaid")(
      test("escapes # character") {
        case class HashEvent(value: String)
        val result = MermaidVisualizer.formatEventForDiagram(HashEvent("test#value"))
        // # becomes #35; but we need to check the original # is gone and #35 is present
        assertTrue(!result.contains("test#value"), result.contains("#35"))
      },
      test("escapes ; character") {
        case class SemiEvent(value: String)
        val result = MermaidVisualizer.formatEventForDiagram(SemiEvent("a;b"))
        assertTrue(result.contains("#59"))
      },
      test("escapes < character") {
        case class LtEvent(value: String)
        val result = MermaidVisualizer.formatEventForDiagram(LtEvent("a<b"))
        assertTrue(result.contains("#60"))
      },
      test("escapes > character") {
        case class GtEvent(value: String)
        val result = MermaidVisualizer.formatEventForDiagram(GtEvent("a>b"))
        assertTrue(result.contains("#62"))
      },
    ),
    suite("duration formatting")(
      test("formats milliseconds") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            (Idle via Start to Processing) @@ Aspect.timeout(500.millis, Retry)
          )
        )
        val result = MermaidVisualizer.stateDiagram(machine)
        assertTrue(result.contains("500ms"))
      },
      test("formats seconds") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            (Idle via Start to Processing) @@ Aspect.timeout(45.seconds, Retry)
          )
        )
        val result = MermaidVisualizer.stateDiagram(machine)
        assertTrue(result.contains("45s"))
      },
      test("formats minutes") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            (Idle via Start to Processing) @@ Aspect.timeout(5.minutes, Retry)
          )
        )
        val result = MermaidVisualizer.stateDiagram(machine)
        assertTrue(result.contains("5m"))
      },
      test("formats hours") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            (Idle via Start to Processing) @@ Aspect.timeout(2.hours, Retry)
          )
        )
        val result = MermaidVisualizer.stateDiagram(machine)
        assertTrue(result.contains("2h"))
      },
    ),
  )
end MermaidVisualizerSpec
