package mechanoid.docs

import mermoid.ascent.MermoidAscent
import mermoid.{ContainerFit, Mermaid, Viewport}
import mechanoid.*
import specular.*
import specular.ziotest.DocSpecSuite
import zio.test.*

/** The site front. Specular still emits a summary index; [[BuildSite]] copies this page over it. */
object Front extends DocSpecSuite:

  import DocumentWorkflow.*

  private def coordinate: String =
    s"""libraryDependencies += "rocks.earlyeffect" %% "mechanoid" % "${DocsVersion.version}"
       |libraryDependencies += "dev.zio" %% "zio" % "2.1.26"
       |
       |libraryDependencies += "rocks.earlyeffect" %%% "mechanoid" % "${DocsVersion.version}"
       |libraryDependencies += "dev.zio" %%% "zio" % "2.1.26"
       |
       |libraryDependencies += "rocks.earlyeffect" %%% "mechanoid-web" % "${DocsVersion.version}"
       |libraryDependencies += "rocks.earlyeffect" %% "mechanoid-postgres" % "${DocsVersion.version}\"""".stripMargin

  /** Document Workflow, including the `InReview` and `Approval` composites that machine declares. */
  private val workflow =
    Mermaid("""stateDiagram-v2
              |  direction TB
              |  [*] --> Draft
              |  state InReview {
              |    direction TB
              |    PendingReview --> UnderReview: AssignReviewer
              |    UnderReview --> ChangesRequested: RequestChanges
              |    ChangesRequested --> PendingReview: ResubmitAfterChanges
              |  }
              |  state Approval {
              |    direction TB
              |    PendingApproval --> Rejected: RejectPublication
              |  }
              |  Draft --> PendingReview: SubmitForReview
              |  UnderReview --> PendingApproval: ApproveReview
              |  PendingApproval --> Published: ApprovePublication
              |  Rejected --> PendingReview: SubmitForReview
              |  PendingReview --> Draft: CancelReview
              |  UnderReview --> Draft: CancelReview
              |  ChangesRequested --> Draft: CancelReview
              |  PendingApproval --> Cancelled: Abandon
              |  Rejected --> Cancelled: Abandon
              |  Published --> Archived: Archive
              |""".stripMargin)

  /** Full-size labels. The column scrolls; the figure does not scale the type down. */
  private val shown =
    DocsDiagram.chalkboard.copy(responsive = DocsDiagram.chalkboard.responsive.copy(fit = ContainerFit.Off))

  private val edgeLine = """^([A-Za-z0-9]+) --> ([A-Za-z0-9]+): ([A-Za-z0-9]+)$""".r

  private val leaves = List(
    Draft,
    PendingReview,
    UnderReview,
    ChangesRequested,
    PendingApproval,
    Rejected,
    Published,
    Archived,
    Cancelled,
  )

  private def diagramEdges: Set[(String, String, String)] =
    workflow.source.linesIterator
      .map(_.trim)
      .collect { case edgeLine(from, to, event) =>
        (from, event, to)
      }
      .toSet

  private def machineEdges: Set[(String, String, String)] =
    val states = summon[Finite[DocumentState]]
    val events = summon[Finite[DocumentEvent]]
    leaves.flatMap { state =>
      DocumentEvent.values.toList.flatMap { event =>
        MachineGraph.destLeaf(machine, state, event).map { dest =>
          (states.nameOf(state), events.nameOf(event), dest)
        }
      }
    }.toSet
  end machineEdges

  def doc = page("Typed machines")(
    md"""States and events are enums, and an illegal assembly does not compile.""",
    section("Document workflow")(
      md"""
This is the machine [Document Workflow](document-workflow.html) runs. `InReview` and `Approval` are composites: `all[InReview]` cancels to `Draft`, and `all[Approval]` abandons to `Cancelled`.
""",
      illustration(MermoidAscent.diagram(workflow, shown, Some(Viewport(390)))).assert { ui =>
        val text = ui.toString
        assertTrue(
          text.contains("mermoid-root"),
          text.contains("InReview"),
          text.contains("Approval"),
          text.contains("CancelReview"),
          text.contains("Abandon"),
          text.contains("Draft"),
        )
      },
    ),
    section("Install")(
      md"""
```scala
${coordinate}
```
"""
    ),
    section("Machine.apply")(
      md"""
`Machine.apply` is the public constructor. Citing that method expands the inline macro, which rejects a placeholder assembly, so the panel is `mechanoid.machine.Machine`, cut after `apply`.
""",
      cite(_root_.mechanoid.machine.Machine).elided(38),
    ),
  )

  override def spec =
    suite("Typed machines")(
      super.spec,
      test("the figure is that machine, composites included") {
        assertTrue(
          workflow.source.contains("state InReview"),
          workflow.source.contains("state Approval"),
          !workflow.source.contains("sequenceDiagram"),
          diagramEdges == machineEdges,
          diagramEdges.exists { case (from, event, to) =>
            from == "PendingReview" && event == "CancelReview" && to == "Draft"
          },
          diagramEdges.exists { case (from, event, to) =>
            from == "Rejected" && event == "Abandon" && to == "Cancelled"
          },
        )
      },
      test("install coordinate is 0.8.0") {
        val version = DocsVersion.version
        assertTrue(
          coordinate.contains(version),
          coordinate.contains("%% \"mechanoid\""),
          coordinate.contains("%%% \"mechanoid-web\""),
          coordinate.contains("%% \"mechanoid-postgres\""),
          version == "0.8.0",
          !version.contains("-ci"),
          !version.contains("SNAPSHOT"),
          !coordinate.contains("-ci"),
          !coordinate.contains("SNAPSHOT"),
          !coordinate.contains("<version>"),
          DocsVersion.advertise("0.8.1-ci") == DocsVersion.published,
          DocsVersion.advertise("0.9.0-ci") == DocsVersion.published,
          DocsVersion.advertise("0.8.0+7-abcdef") == DocsVersion.published,
          DocsVersion.advertise("0.9.0-SNAPSHOT") == DocsVersion.published,
          DocsVersion.advertise("0.8.0") == "0.8.0",
        )
      },
    )
end Front
