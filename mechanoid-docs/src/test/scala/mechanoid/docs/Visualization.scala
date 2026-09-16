package mechanoid.docs

import mechanoid.*
import specular.*
import specular.mermoid.Mermoid
import zio.*
import zio.test.*

object Visualization extends MechanoidDocSpecSuite:

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

        Mermoid.diagram(
          MermaidVisualizer.stateDiagram(machine, Some(Created)),
          Mermoid.chalkboard,
        )
      }.assert(ui => assertTrue(ui.toString.nonEmpty)),
    ),
    section("Flowchart")(
      md"""
`MermaidVisualizer.flowchart(machine)` is often clearer for dense graphs (Domain pages use it):
""",
      example {
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

        Mermoid.diagram(
          MermaidVisualizer.flowchart(machine),
          Mermoid.chalkboard,
        )
      }.assert(ui => assertTrue(ui.toString.nonEmpty)),
    ),
    section("Query the graph")(
      md"""
`MachineGraph` reads the same assembly edges Mermaid walks. `hasEdge` is the declared transition,
not reducer success. `destLeaf` is the Goto leaf name, or the current leaf for Stay / Stop
(`None` when there is no edge). Call sites do not hash Finite cases.
""",
      example {
        enum OrderState derives Finite:
          case Created, Processing, Completed

        enum OrderEvent derives Finite:
          case Start, Finish

        import OrderState.*, OrderEvent.*

        val machine = Machine(
          assembly[OrderState, OrderEvent](
            Created via Start to Processing,
            Processing via Finish to Completed,
            Processing via Start to stay,
          )
        )

        Mermoid.diagram(
          MermaidVisualizer.stateDiagram(machine, Some(Created)),
          Mermoid.chalkboard,
        )
      }.assert(ui => assertTrue(ui.toString.nonEmpty)),
      exampleZIO {
        enum OrderState derives Finite:
          case Created, Processing, Completed

        enum OrderEvent derives Finite:
          case Start, Finish

        import OrderState.*, OrderEvent.*

        val machine = Machine(
          assembly[OrderState, OrderEvent](
            Created via Start to Processing,
            Processing via Finish to Completed,
            Processing via Start to stay,
          )
        )

        val startEdge  = MachineGraph.hasEdge(machine, Created, Start)
        val startDest  = MachineGraph.destLeaf(machine, Created, Start)
        val finishEdge = MachineGraph.hasEdge(machine, Created, Finish)
        val finishDest = MachineGraph.destLeaf(machine, Created, Finish)
        val stayDest   = MachineGraph.destLeaf(machine, Processing, Start)
        ZIO.succeed(
          List(
            s"hasEdge(Created, Start) = $startEdge",
            s"destLeaf(Created, Start) = $startDest",
            s"hasEdge(Created, Finish) = $finishEdge",
            s"destLeaf(Created, Finish) = $finishDest",
            s"destLeaf(Processing, Start) = $stayDest",
          ).mkString("\n")
        )
      }.assert { shown =>
        assertTrue(
          shown.contains("hasEdge(Created, Start) = true"),
          shown.contains("destLeaf(Created, Start) = Some(Processing)"),
          shown.contains("hasEdge(Created, Finish) = false"),
          shown.contains("destLeaf(Created, Finish) = None"),
          shown.contains("destLeaf(Processing, Start) = Some(Processing)"),
        )
      },
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
