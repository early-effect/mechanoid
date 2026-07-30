package mechanoid.docs

import mechanoid.*
import specular.*
import zio.test.*

object Visualization extends MechanoidDocSpecSuite:

  enum OrderState derives Finite:
    case Created, Processing, Completed

  enum OrderEvent derives Finite:
    case Start, Finish

  import OrderState.*, OrderEvent.*

  val machine = Machine(
    assembly[OrderState, OrderEvent](
      Created via Start to Processing,
      Processing via Finish to Completed,
    )
  )

  def doc = page("Visualization")(
    md"""
Mechanoid can emit Mermaid and GraphViz from the same `Machine` you run. Useful for docs,
debugging traces, and sharing designs.
""",
    section("Mermaid from a Machine")(
      md"""
Extension methods on `Machine` (via `import mechanoid.*`):

- `toMermaidStateDiagram(initialState)`
- `toMermaidFlowchart`
- `toMermaidFlowchartWithTrace(trace)`
- `toGraphViz(...)` / `toGraphVizWithTrace(...)`
""",
      exampleValue {
        MermaidVisualizer.stateDiagram(machine, Some(Created))
      }.assert { diagram =>
        assertTrue(diagram.contains("stateDiagram")) &&
        assertTrue(diagram.contains("Created")) &&
        assertTrue(diagram.contains("Processing"))
      },
      exampleValue {
        MermaidVisualizer.flowchart(machine)
      }.assert { flowchart =>
        assertTrue(flowchart.contains("flowchart") || flowchart.contains("Created")) &&
        assertTrue(flowchart.contains("Start") || flowchart.contains("Processing"))
      },
    ),
    section("Traces")(
      md"""
`ExecutionTrace` supports sequence diagrams and GraphViz timelines:

```scala
trace.toMermaidSequenceDiagram
trace.toGraphVizTimeline
```

Or call `MermaidVisualizer` / `GraphVizVisualizer` static APIs directly.

Paste Mermaid output into GitHub, GitLab, or this Specular site's fenced blocks. The teaching
diagrams elsewhere on the site are hand-authored; these APIs keep **your** machines in sync
with the pictures.

Next: [Reference](reference.html).
"""
    ),
  )
end Visualization
