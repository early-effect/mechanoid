package mechanoid.docs

import mechanoid.*
import specular.*
import specular.mermoid.Mermoid
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
debugging traces, and sharing designs. Below, Mechanoid generates the Mermaid source and
[mermoid](https://www.earlyeffect.rocks/mermoid/) renders it on the page — parse failures fail CI.
""",
    section("Mermaid from a Machine")(
      md"""
Extension methods on `Machine` (via `import mechanoid.*`):

- `toMermaidStateDiagram(initialState)`
- `toMermaidFlowchart`
- `toMermaidFlowchartWithTrace(trace)`
- `toGraphViz(...)` / `toGraphVizWithTrace(...)`

State diagram from `MermaidVisualizer.stateDiagram(machine, Some(Created))`:
""",
      example {
        Mermoid.diagram(
          MermaidVisualizer.stateDiagram(machine, Some(Created)),
          DocsDiagrams.diagramConfig,
        )
      }.assert { ui =>
        assertTrue(ui.toString.nonEmpty)
      },
      md"""
Flowchart from `MermaidVisualizer.flowchart(machine)`:
""",
      example {
        Mermoid.diagram(
          MermaidVisualizer.flowchart(machine),
          DocsDiagrams.diagramConfig,
        )
      }.assert { ui =>
        assertTrue(ui.toString.nonEmpty)
      },
    ),
    section("Traces")(
      md"""
`ExecutionTrace` supports sequence diagrams and GraphViz timelines:

```scala
trace.toMermaidSequenceDiagram
trace.toGraphVizTimeline
```

Or call `MermaidVisualizer` / `GraphVizVisualizer` static APIs directly. Feed the Mermaid
strings into `Mermoid.diagram(...)` (as above) or a fenced `mermaid` block so the picture
stays in sync with the machine definition.

Next: [Reference](reference.html).
"""
    ),
  )
end Visualization
