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
Mechanoid emits Mermaid (and GraphViz) from the same `Machine` you run. This site feeds that
source into [mermoid](https://www.earlyeffect.rocks/mermoid/) so the published picture fails CI
if the Mermaid cannot parse.
""",
    section("State diagram")(
      md"""
`MermaidVisualizer.stateDiagram(machine, Some(Created))` (also
`machine.toMermaidStateDiagram(Some(Created))`):
""",
      example {
        Mermoid.diagram(
          MermaidVisualizer.stateDiagram(machine, Some(Created)),
          DocsDiagrams.diagramConfig,
        )
      }.assert(ui => assertTrue(ui.toString.nonEmpty)),
    ),
    section("Flowchart")(
      md"""
`MermaidVisualizer.flowchart(machine)` is often clearer for dense graphs (Domain pages use it):
""",
      example {
        Mermoid.diagram(
          MermaidVisualizer.flowchart(machine),
          DocsDiagrams.diagramConfig,
        )
      }.assert(ui => assertTrue(ui.toString.nonEmpty)),
    ),
    section("Traces and GraphViz")(
      md"""
Also available:

- `toMermaidFlowchartWithTrace(trace)` / `trace.toMermaidSequenceDiagram`
- `toGraphViz(...)` / `toGraphVizWithTrace(...)` / `trace.toGraphVizTimeline`

Mark sensitive fields with `@sensitive` when exporting. Feed Mermaid strings into
`Mermoid.diagram(...)` or a fenced `mermaid` block.

Next: [Reference](reference.html).
"""
    ),
  )
end Visualization
