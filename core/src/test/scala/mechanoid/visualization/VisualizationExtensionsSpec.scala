package mechanoid.visualization

import zio.*
import zio.test.*
import mechanoid.core.Finite
import mechanoid.machine.*
import java.time.Instant

object VisualizationExtensionsSpec extends ZIOSpecDefault:

  enum TestState derives Finite:
    case Idle, Processing, Completed

  enum TestEvent derives Finite:
    case Start, Finish

  import TestState.*
  import TestEvent.*

  def spec = suite("VisualizationExtensionsSpec")(
    suite("Machine extensions")(
      test("toMermaidStateDiagram generates state diagram") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            Idle via Start to Processing,
            Processing via Finish to Completed,
          )
        )
        val result = machine.toMermaidStateDiagram()
        assertTrue(
          result.startsWith("stateDiagram-v2"),
          result.contains("Idle --> Processing: Start"),
        )
      },
      test("toMermaidStateDiagram with initial state") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            Idle via Start to Processing
          )
        )
        val result = machine.toMermaidStateDiagram(Some(Idle))
        assertTrue(result.contains("[*] --> Idle"))
      },
      test("toMermaidFlowchart generates flowchart") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            Idle via Start to Processing
          )
        )
        val result = machine.toMermaidFlowchart
        assertTrue(
          result.startsWith("flowchart LR"),
          result.contains("Idle((Idle))"),
        )
      },
      test("toMermaidFlowchartWithTrace highlights visited states") {
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
        val result = machine.toMermaidFlowchartWithTrace(trace)
        assertTrue(
          result.contains("style Idle fill:#ADD8E6"),
          result.contains("style Processing fill:#90EE90"),
        )
      },
      test("toGraphViz generates digraph") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            Idle via Start to Processing
          )
        )
        val result = machine.toGraphViz()
        assertTrue(
          result.contains("digraph FSM {"),
          result.contains("Idle -> Processing"),
        )
      },
      test("toGraphViz with custom name and initial state") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            Idle via Start to Processing
          )
        )
        val result = machine.toGraphViz(name = "MyMachine", initialState = Some(Idle))
        assertTrue(
          result.contains("digraph MyMachine {"),
          result.contains("__start__ -> Idle"),
        )
      },
      test("toGraphViz with custom config") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            Idle via Start to Processing
          )
        )
        val config = GraphVizVisualizer.Config(rankDir = "TB", nodeShape = "box")
        val result = machine.toGraphViz(config = config)
        assertTrue(
          result.contains("rankdir=TB"),
          result.contains("node [shape=box"),
        )
      },
      test("toGraphVizWithTrace highlights current state") {
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
        val result = machine.toGraphVizWithTrace(trace)
        assertTrue(
          result.contains("fillcolor=\"#90EE90\""),
          result.contains("penwidth=2, color=blue"),
        )
      },
    ),
    suite("ExecutionTrace extensions")(
      test("toMermaidSequenceDiagram generates sequence diagram") {
        given Finite[TestState] = summon[Finite[TestState]]
        given Finite[TestEvent] = summon[Finite[TestEvent]]

        val trace = ExecutionTrace(
          "test-instance",
          Idle,
          Processing,
          List(TraceStep(1, Idle, Processing, Start, Instant.now(), false)),
        )
        val result = trace.toMermaidSequenceDiagram
        assertTrue(
          result.startsWith("sequenceDiagram"),
          result.contains("participant FSM as test-instance"),
          result.contains("FSM->>FSM: Start"),
        )
      },
      test("toGraphVizTimeline generates timeline") {
        given Finite[TestState] = summon[Finite[TestState]]
        given Finite[TestEvent] = summon[Finite[TestEvent]]

        val trace = ExecutionTrace(
          "test-instance",
          Idle,
          Processing,
          List(TraceStep(1, Idle, Processing, Start, Instant.now(), false)),
        )
        val result = trace.toGraphVizTimeline
        assertTrue(
          result.contains("digraph Timeline {"),
          result.contains("rankdir=LR"),
          result.contains("s0 [label=\"Idle\""),
          result.contains("s1 [label=\"Processing\""),
        )
      },
    ),
  )
end VisualizationExtensionsSpec
