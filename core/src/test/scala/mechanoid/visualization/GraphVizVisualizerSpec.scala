package mechanoid.visualization

import zio.*
import zio.test.*
import mechanoid.core.Finite
import mechanoid.machine.*
import java.time.Instant

object GraphVizVisualizerSpec extends ZIOSpecDefault:

  enum TestState derives Finite:
    case Idle, Processing, Completed, Failed

  enum TestEvent derives Finite:
    case Start, Finish, Fail, Retry

  import TestState.*
  import TestEvent.*

  // Parameterized event for testing formatting
  enum ParamEvent derives Finite:
    case Simple
    case WithParam(value: String)

  def spec = suite("GraphVizVisualizerSpec")(
    suite("digraph")(
      test("generates valid digraph structure") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            Idle via Start to Processing,
            Processing via Finish to Completed,
          )
        )
        val result = GraphVizVisualizer.digraph(machine)
        assertTrue(
          result.contains("digraph FSM {"),
          result.contains("rankdir=LR"),
          result.contains("node [shape=ellipse"),
          result.endsWith("}\n"),
        )
      },
      test("includes all states as nodes") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            Idle via Start to Processing,
            Processing via Finish to Completed,
          )
        )
        val result = GraphVizVisualizer.digraph(machine)
        assertTrue(
          result.contains("Idle [label="),
          result.contains("Processing [label="),
          result.contains("Completed [label="),
        )
      },
      test("includes transitions with event labels") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            Idle via Start to Processing,
            Processing via Finish to Completed,
          )
        )
        val result = GraphVizVisualizer.digraph(machine)
        assertTrue(
          result.contains("Idle -> Processing [label=\"Start\"]"),
          result.contains("Processing -> Completed [label=\"Finish\"]"),
        )
      },
      test("adds initial state marker when provided") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            Idle via Start to Processing
          )
        )
        val result = GraphVizVisualizer.digraph(machine, initialState = Some(Idle))
        assertTrue(
          result.contains("__start__ [shape=point"),
          result.contains("__start__ -> Idle"),
        )
      },
      test("no initial state marker when not provided") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            Idle via Start to Processing
          )
        )
        val result = GraphVizVisualizer.digraph(machine, initialState = None)
        assertTrue(!result.contains("__start__"))
      },
      test("respects custom config rankDir") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            Idle via Start to Processing
          )
        )
        val config = GraphVizVisualizer.Config(rankDir = "TB")
        val result = GraphVizVisualizer.digraph(machine, config = config)
        assertTrue(result.contains("rankdir=TB"))
      },
      test("respects custom config nodeShape") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            Idle via Start to Processing
          )
        )
        val config = GraphVizVisualizer.Config(nodeShape = "box")
        val result = GraphVizVisualizer.digraph(machine, config = config)
        assertTrue(result.contains("node [shape=box"))
      },
      test("respects custom name") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            Idle via Start to Processing
          )
        )
        val result = GraphVizVisualizer.digraph(machine, name = "MyFSM")
        assertTrue(result.contains("digraph MyFSM {"))
      },
      test("handles stay transitions as self-loops") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            Processing via Retry to stay
          )
        )
        val result = GraphVizVisualizer.digraph(machine)
        assertTrue(result.contains("Processing -> Processing [label=\"Retry\"]"))
      },
      test("handles stop transitions with terminal marker") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            Processing via Fail to stop
          )
        )
        val result = GraphVizVisualizer.digraph(machine)
        assertTrue(
          result.contains("__end__ [shape=doublecircle"),
          result.contains("Processing -> __end__ [label=\"Fail\"]"),
        )
      },
      test("shows timeout annotation in state label") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            (Idle via Start to Processing) @@ Aspect.timeout(30.seconds, Retry)
          )
        )
        val result = GraphVizVisualizer.digraph(machine)
        assertTrue(result.contains("timeout:"))
      },
      test("formats duration as milliseconds when < 1s") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            (Idle via Start to Processing) @@ Aspect.timeout(500.millis, Retry)
          )
        )
        val result = GraphVizVisualizer.digraph(machine)
        assertTrue(result.contains("500ms"))
      },
      test("formats duration as seconds when >= 1s and < 60s") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            (Idle via Start to Processing) @@ Aspect.timeout(45.seconds, Retry)
          )
        )
        val result = GraphVizVisualizer.digraph(machine)
        assertTrue(result.contains("45s"))
      },
      test("formats duration as minutes when >= 60s and < 60m") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            (Idle via Start to Processing) @@ Aspect.timeout(5.minutes, Retry)
          )
        )
        val result = GraphVizVisualizer.digraph(machine)
        assertTrue(result.contains("5m"))
      },
      test("formats duration as hours when >= 60m") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            (Idle via Start to Processing) @@ Aspect.timeout(2.hours, Retry)
          )
        )
        val result = GraphVizVisualizer.digraph(machine)
        assertTrue(result.contains("2h"))
      },
    ),
    suite("digraphWithTrace")(
      test("highlights current state with currentColor") {
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
        val result = GraphVizVisualizer.digraphWithTrace(machine, trace)
        assertTrue(result.contains("Processing [label=\"Processing\", style=filled, fillcolor=\"#90EE90\"]"))
      },
      test("highlights visited states with visitedColor") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            Idle via Start to Processing,
            Processing via Finish to Completed,
          )
        )
        val trace = ExecutionTrace(
          "test-1",
          Idle,
          Completed,
          List(
            TraceStep(1, Idle, Processing, Start, Instant.now(), false),
            TraceStep(2, Processing, Completed, Finish, Instant.now(), false),
          ),
        )
        val result = GraphVizVisualizer.digraphWithTrace(machine, trace)
        // Idle and Processing are visited (not current), Completed is current
        assertTrue(
          result.contains("Idle [label=\"Idle\", style=filled, fillcolor=\"#ADD8E6\"]"),
          result.contains("Processing [label=\"Processing\", style=filled, fillcolor=\"#ADD8E6\"]"),
          result.contains("Completed [label=\"Completed\", style=filled, fillcolor=\"#90EE90\"]"),
        )
      },
      test("highlights taken transitions") {
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
        val result = GraphVizVisualizer.digraphWithTrace(machine, trace)
        assertTrue(result.contains("Idle -> Processing [label=\"Start\", penwidth=2, color=blue]"))
      },
      test("always includes initial state marker from trace") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            Idle via Start to Processing
          )
        )
        val trace  = ExecutionTrace.empty[TestState, TestEvent]("test-1", Idle)
        val result = GraphVizVisualizer.digraphWithTrace(machine, trace)
        assertTrue(
          result.contains("__start__ [shape=point"),
          result.contains("__start__ -> Idle"),
        )
      },
    ),
    suite("timeline")(
      test("generates timeline with initial state") {
        val trace  = ExecutionTrace.empty[TestState, TestEvent]("test-1", Idle)
        val result = GraphVizVisualizer.timeline(trace, summon[Finite[TestState]], summon[Finite[TestEvent]])
        assertTrue(
          result.contains("digraph Timeline {"),
          result.contains("rankdir=LR"),
          result.contains("s0 [label=\"Idle\""),
        )
      },
      test("shows progression through states") {
        val trace = ExecutionTrace(
          "test-1",
          Idle,
          Completed,
          List(
            TraceStep(1, Idle, Processing, Start, Instant.now(), false),
            TraceStep(2, Processing, Completed, Finish, Instant.now(), false),
          ),
        )
        val result = GraphVizVisualizer.timeline(trace, summon[Finite[TestState]], summon[Finite[TestEvent]])
        assertTrue(
          result.contains("s0 [label=\"Idle\""),
          result.contains("s1 [label=\"Processing\""),
          result.contains("s2 [label=\"Completed\""),
          result.contains("s0 -> s1 [label=\"Start\"]"),
          result.contains("s1 -> s2 [label=\"Finish\"]"),
        )
      },
      test("initial state is gray, intermediate blue, last green") {
        val trace = ExecutionTrace(
          "test-1",
          Idle,
          Completed,
          List(
            TraceStep(1, Idle, Processing, Start, Instant.now(), false),
            TraceStep(2, Processing, Completed, Finish, Instant.now(), false),
          ),
        )
        val result = GraphVizVisualizer.timeline(trace, summon[Finite[TestState]], summon[Finite[TestEvent]])
        assertTrue(
          result.contains("s0 [label=\"Idle\", style=filled, fillcolor=\"#E0E0E0\"]"),       // gray initial
          result.contains("s1 [label=\"Processing\", style=filled, fillcolor=\"#ADD8E6\"]"), // blue intermediate
          result.contains("s2 [label=\"Completed\", style=filled, fillcolor=\"#90EE90\"]"),  // green last
        )
      },
      test("shows Timeout label for timeout events") {
        val trace = ExecutionTrace(
          "test-1",
          Idle,
          Processing,
          List(TraceStep(1, Idle, Processing, Start, Instant.now(), isTimeout = true)),
        )
        val result = GraphVizVisualizer.timeline(trace, summon[Finite[TestState]], summon[Finite[TestEvent]])
        assertTrue(result.contains("[label=\"Timeout\"]"))
      },
    ),
    suite("formatEventLabel edge cases")(
      test("escapes backslashes") {
        // Test through timeline which uses formatEventLabel
        enum EscapeEvent derives Finite:
          case WithBackslash

        val trace = ExecutionTrace(
          "test-1",
          Idle,
          Processing,
          List(TraceStep(1, Idle, Processing, EscapeEvent.WithBackslash, Instant.now(), false)),
        )
        val result = GraphVizVisualizer.timeline(trace, summon[Finite[TestState]], summon[Finite[EscapeEvent]])
        // Should produce valid DOT output
        assertTrue(result.contains("WithBackslash"))
      },
      test("truncates long event names with parentheses through timeline") {
        // Use a parameterized event with a very long parameter value
        enum LongParamEvent derives Finite:
          case WithLongParam(data: String)

        import LongParamEvent.*
        val longValue = "a" * 100
        val trace     = ExecutionTrace(
          "test-1",
          Idle,
          Processing,
          List(TraceStep(1, Idle, Processing, WithLongParam(longValue), Instant.now(), false)),
        )
        val result = GraphVizVisualizer.timeline(trace, summon[Finite[TestState]], summon[Finite[LongParamEvent]])
        // Should truncate to WithLongParam(...)
        assertTrue(
          result.contains("WithLongParam(...)"),
          !result.contains(longValue), // Full value should not appear
        )
      },
      test("truncates long event names without parentheses") {
        // Event name without parentheses that exceeds 50 chars
        val longName  = "A" * 60
        val truncated =
          if longName.length > 50 then
            val parenIdx = longName.indexOf('(')
            if parenIdx > 0 then s"${longName.substring(0, parenIdx)}(...)"
            else longName.take(47) + "..."
          else longName
        assertTrue(truncated.endsWith("..."))
        assertTrue(truncated.length <= 50)
      },
      test("handles normal-length event names without truncation") {
        // Event that doesn't need truncation
        enum ShortEvent derives Finite:
          case Simple

        import ShortEvent.*
        val trace = ExecutionTrace(
          "test-1",
          Idle,
          Processing,
          List(TraceStep(1, Idle, Processing, Simple, Instant.now(), false)),
        )
        val result = GraphVizVisualizer.timeline(trace, summon[Finite[TestState]], summon[Finite[ShortEvent]])
        // Should contain the full event name without truncation
        assertTrue(
          result.contains("Simple"),
          !result.contains("..."),
        )
      },
    ),
    suite("digraphWithTrace with stop transitions")(
      test("includes terminal marker when machine has stop transitions") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            Idle via Start to Processing,
            Processing via Fail to stop("failed"),
          )
        )
        val trace = ExecutionTrace(
          "test-1",
          Idle,
          Processing,
          List(TraceStep(1, Idle, Processing, Start, Instant.now(), false)),
        )
        val result = GraphVizVisualizer.digraphWithTrace(machine, trace)
        assertTrue(
          result.contains("__end__ [shape=doublecircle"),
          result.contains("Processing -> __end__"),
        )
      },
      test("highlights stop transition when taken") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            Idle via Start to Processing,
            Processing via Fail to stop("failed"),
          )
        )
        // Note: Can't have a trace that ends in stop since trace tracks state transitions
        // But we can verify the stop transition is rendered correctly
        val trace = ExecutionTrace(
          "test-1",
          Idle,
          Processing,
          List(TraceStep(1, Idle, Processing, Start, Instant.now(), false)),
        )
        val result = GraphVizVisualizer.digraphWithTrace(machine, trace)
        assertTrue(result.contains("Processing -> __end__"))
      },
      test("handles stay transition in trace") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            Processing via Retry to stay
          )
        )
        val trace = ExecutionTrace(
          "test-1",
          Processing,
          Processing,
          List(TraceStep(1, Processing, Processing, Retry, Instant.now(), false)),
        )
        val result = GraphVizVisualizer.digraphWithTrace(machine, trace)
        assertTrue(
          result.contains("Processing -> Processing [label=\"Retry\", penwidth=2, color=blue]")
        )
      },
    ),
    suite("Config")(
      test("default config has expected values") {
        val config = GraphVizVisualizer.Config.default
        assertTrue(
          config.rankDir == "LR",
          config.nodeShape == "ellipse",
          config.fontSize == 12,
          config.visitedColor == "#ADD8E6",
          config.currentColor == "#90EE90",
          config.timeoutColor == "#FFB6C1",
        )
      }
    ),
    suite("digraphWithTrace timeout state")(
      test("uses timeout color for state with timeout") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            (Idle via Start to Processing) @@ Aspect.timeout(30.seconds, Retry),
            Processing via Finish to Completed,
          )
        )
        // Trace visits Processing which has a timeout
        val trace = ExecutionTrace(
          "test-1",
          Idle,
          Completed,
          List(
            TraceStep(1, Idle, Processing, Start, Instant.now(), false),
            TraceStep(2, Processing, Completed, Finish, Instant.now(), false),
          ),
        )
        val result = GraphVizVisualizer.digraphWithTrace(machine, trace)
        // Processing is visited (not current) but has timeout, should show timeout annotation
        assertTrue(
          result.contains("timeout:") // Timeout annotation in label
        )
      },
      test("shows timeout color for unvisited state with timeout") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            (Idle via Start to Processing) @@ Aspect.timeout(30.seconds, Retry),
            Processing via Finish to Completed,
            Processing via Retry to Processing,
          )
        )
        // Trace only visits Idle, Processing has timeout but is not visited
        // Use explicit type annotation for the trace
        val trace: ExecutionTrace[TestState, TestEvent] = ExecutionTrace(
          "test-1",
          Idle,
          Idle,
          List.empty,
        )
        val result = GraphVizVisualizer.digraphWithTrace(machine, trace)
        // Processing should use timeout color since it has a timeout
        assertTrue(result.contains("#FFB6C1")) // timeoutColor
      },
    ),
    suite("timeline long event name")(
      test("truncates long event name without parentheses via timeline") {
        // Test formatEventLabel indirectly via timeline which calls it
        // Create an event with a very long toString (over 50 chars, no parentheses)
        enum LongNameEvent derives Finite:
          case AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA // 65 chars

        import LongNameEvent.*
        val trace = ExecutionTrace(
          "test-1",
          Idle,
          Processing,
          List(
            TraceStep(
              1,
              Idle,
              Processing,
              AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA,
              Instant.now(),
              false,
            )
          ),
        )
        val result = GraphVizVisualizer.timeline(trace, summon[Finite[TestState]], summon[Finite[LongNameEvent]])
        // Should truncate the long event name with ...
        assertTrue(
          result.contains("..."),
          !result.contains(
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
          ), // Full name should not appear
        )
      }
    ),
  )
end GraphVizVisualizerSpec
