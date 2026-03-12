# Visualization

[Back to Documentation Index](DOCUMENTATION.md)

---

Mechanoid provides built-in visualization tools to generate diagrams of your FSM structure and execution traces. These visualizations are invaluable for:

- **Documentation**: Auto-generate up-to-date FSM diagrams
- **Debugging**: Visualize execution traces to understand state transitions
- **Communication**: Share FSM designs with stakeholders using familiar diagram formats

## Visualization Overview

Two visualizers are available:

| Visualizer | Output Format | Best For |
|------------|---------------|----------|
| `MermaidVisualizer` | Mermaid markdown | GitHub/GitLab READMEs, documentation sites |
| `GraphVizVisualizer` | DOT format | High-quality rendered images, complex diagrams |

Both visualizers work with any FSM definition.

## MermaidVisualizer

Generate [Mermaid](https://mermaid.js.org/) diagrams that render directly in GitHub, GitLab, and many documentation tools.

### Extension Methods

FSM definitions have extension methods for convenient visualization:

```scala mdoc:reset:silent
import mechanoid.*
import zio.*

enum OrderState derives Finite:
  case Created, Processing, Completed

enum OrderEvent derives Finite:
  case Start, Finish

import OrderState.*, OrderEvent.*

val machine = Machine(assembly[OrderState, OrderEvent](
  Created via Start to Processing,
  Processing via Finish to Completed,
))

val trace: ExecutionTrace[OrderState, OrderEvent] = ExecutionTrace.empty("instance-1", Created)
```

```scala mdoc:compile-only
// State diagram using extension method
val diagram = machine.toMermaidStateDiagram(Some(OrderState.Created))

// Flowchart
val flowchart = machine.toMermaidFlowchart

// With execution trace highlighting
val highlighted = machine.toMermaidFlowchartWithTrace(trace)

// GraphViz
val dot = machine.toGraphViz(name = "OrderFSM", initialState = Some(OrderState.Created))
```

Execution traces also have extension methods:

```scala mdoc:compile-only
val sequenceDiagram = trace.toMermaidSequenceDiagram
val timeline = trace.toGraphVizTimeline
```

### State Diagram (Static Methods)

Shows the FSM structure with all states and transitions:

```scala mdoc:compile-only
// Basic state diagram using static method
val diagram = MermaidVisualizer.stateDiagram(
  fsm = machine,
  initialState = Some(OrderState.Created)
)

// Output:
// stateDiagram-v2
//     [*] --> Created
//     Created --> PaymentProcessing: InitiatePayment
//     PaymentProcessing --> Paid: PaymentSucceeded
//     ...
```

### Sequence Diagram

Shows an execution trace as a sequence of state transitions:

```scala mdoc:compile-only
val sequenceDiagram = MermaidVisualizer.sequenceDiagram(
  trace = trace,
  stateEnum = summon[Finite[OrderState]],
  eventEnum = summon[Finite[OrderEvent]]
)
```

### Flowchart

Shows the FSM as a flowchart with highlighted execution path:

```scala mdoc:compile-only
val flowchart = MermaidVisualizer.flowchart(
  fsm = machine,
  trace = Some(trace)  // Optional: highlights visited states
)
```

## GraphVizVisualizer

Generate [GraphViz DOT](https://graphviz.org/) format for high-quality rendered diagrams.

```scala mdoc:compile-only
// Basic digraph
val dot = GraphVizVisualizer.digraph(
  fsm = machine,
  initialState = Some(OrderState.Created)
)

// Output:
// digraph FSM {
//     rankdir=LR;
//     node [shape=ellipse];
//     Created -> PaymentProcessing [label="InitiatePayment"];
//     ...
// }

// With execution trace highlighting
val dotWithTrace = GraphVizVisualizer.digraphWithTrace(
  fsm = machine,
  trace = trace
)
```

Render the DOT output using GraphViz tools:

```bash
# Generate PNG
dot -Tpng fsm.dot -o fsm.png

# Generate SVG
dot -Tsvg fsm.dot -o fsm.svg
```

## Generating Visualizations

Here's a complete example that generates all visualization types:

```scala mdoc:compile-only
import java.nio.file.{Files, Paths}

def generateVisualizations[S, E](
    machine: Machine[S, E],
    initialState: S,
    outputDir: String
)(using Finite[S], Finite[E]): ZIO[Any, Throwable, Unit] =
  for
    _ <- ZIO.attempt(Files.createDirectories(Paths.get(outputDir)))

    // Generate FSM structure diagram
    structureMd = s"""# FSM Structure
                     |
                     |## State Diagram
                     |
                     |```mermaid
                     |${MermaidVisualizer.stateDiagram(machine, Some(initialState))}
                     |```
                     |
                     |## Flowchart
                     |
                     |```mermaid
                     |${MermaidVisualizer.flowchart(machine)}
                     |```
                     |
                     |## GraphViz
                     |
                     |```dot
                     |${GraphVizVisualizer.digraph(machine, initialState = Some(initialState))}
                     |```
                     |""".stripMargin

    _ <- ZIO.attempt(
      Files.writeString(Paths.get(s"$outputDir/fsm-structure.md"), structureMd)
    )
  yield ()
```

## Example Outputs

See the [visualizations directory](visualizations/) for complete examples:

- [Order FSM Structure](visualizations/order-fsm-structure.md) - FSM definition with state diagram, flowchart, and GraphViz
- [Order 1 Trace](visualizations/order-1-trace.md) - Successful order execution trace
- [Order 5 Trace](visualizations/order-5-trace.md) - Failed order (payment declined) trace

---

[<< Previous: Side Effects](side-effects.md) | [Back to Index](DOCUMENTATION.md) | [Next: Reference >>](reference.md)
