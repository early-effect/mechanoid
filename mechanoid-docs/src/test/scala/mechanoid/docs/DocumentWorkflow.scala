package mechanoid.docs

import mechanoid.docs.DocZIO.*
import mechanoid.*
import specular.*
import specular.mermoid.Mermoid
import zio.*
import zio.test.*

/** Document workflow domain (from `examples/hierarchical`). */
object DocumentWorkflow extends MechanoidDocSpecSuite:

  sealed trait DocumentState derives Finite
  case object Draft extends DocumentState

  sealed trait InReview extends DocumentState derives Finite
  case object PendingReview    extends InReview
  case object UnderReview      extends InReview
  case object ChangesRequested extends InReview

  sealed trait Approval extends DocumentState derives Finite
  case object PendingApproval extends Approval
  case object Rejected        extends Approval

  case object Published extends DocumentState
  case object Archived  extends DocumentState
  case object Cancelled extends DocumentState

  enum DocumentEvent derives Finite:
    case SubmitForReview, AssignReviewer, RequestChanges, ResubmitAfterChanges
    case ApproveReview, ApprovePublication, RejectPublication, Archive
    case CancelReview, Abandon

  import DocumentEvent.*

  val machine = Machine(
    assembly[DocumentState, DocumentEvent](
      all[InReview] via CancelReview to Draft,
      all[Approval] via Abandon to Cancelled,
    ) ++ assembly[DocumentState, DocumentEvent](
      Draft via SubmitForReview to PendingReview,
      PendingReview via AssignReviewer to UnderReview,
      UnderReview via RequestChanges to ChangesRequested,
      UnderReview via ApproveReview to PendingApproval,
      ChangesRequested via ResubmitAfterChanges to PendingReview,
      PendingApproval via ApprovePublication to Published,
      PendingApproval via RejectPublication to Rejected,
      Rejected via SubmitForReview to PendingReview,
      Published via Archive to Archived,
    )
  )

  private val machineSource =
    md"""
```scala
sealed trait DocumentState derives Finite
case object Draft extends DocumentState
sealed trait InReview extends DocumentState derives Finite
case object PendingReview extends InReview
case object UnderReview extends InReview
case object ChangesRequested extends InReview
sealed trait Approval extends DocumentState derives Finite
case object PendingApproval extends Approval
case object Rejected extends Approval
case object Published extends DocumentState
case object Archived extends DocumentState
case object Cancelled extends DocumentState

enum DocumentEvent derives Finite:
  case SubmitForReview, AssignReviewer, RequestChanges, ResubmitAfterChanges
  case ApproveReview, ApprovePublication, RejectPublication, Archive
  case CancelReview, Abandon

val machine = Machine(
  assembly[DocumentState, DocumentEvent](
    all[InReview] via CancelReview to Draft,
    all[Approval] via Abandon to Cancelled,
  ) ++ assembly[DocumentState, DocumentEvent](
    Draft via SubmitForReview to PendingReview,
    PendingReview via AssignReviewer to UnderReview,
    UnderReview via RequestChanges to ChangesRequested,
    UnderReview via ApproveReview to PendingApproval,
    ChangesRequested via ResubmitAfterChanges to PendingReview,
    PendingApproval via ApprovePublication to Published,
    PendingApproval via RejectPublication to Rejected,
    Rejected via SubmitForReview to PendingReview,
    Published via Archive to Archived,
  )
)
```
"""

  def doc = page("Document Workflow")(
    md"""
Hierarchical states keep review and approval phases readable. Nested sealed traits group leaves;
`all[T]` applies one transition to every leaf under a parent; fragments compose with `++`.

Teaching slice of `examples/.../hierarchical`. The machine used by every example on this page:
""",
    machineSource,
    section("The graph")(
      example {
        Mermoid.diagram(
          MermaidVisualizer.flowchart(machine),
          DocsDiagrams.diagramConfig,
        )
      }.assert(ui => assertTrue(ui.toString.nonEmpty)),
      md"""
Look for the group edges: every `InReview` leaf can `CancelReview` back to `Draft`, and every
`Approval` leaf can `Abandon` to `Cancelled`.
""",
    ),
    section("Publish path")(
      md"""Draft → review → approval → `Published`:""",
      exampleZIO {
        ZIO.scoped {
          for
            fsm   <- machine.start(Draft)
            _     <- fsm.send(SubmitForReview)
            _     <- fsm.send(AssignReviewer)
            _     <- fsm.send(ApproveReview)
            _     <- fsm.send(ApprovePublication)
            state <- fsm.currentState
          yield state
        }.asDoc
      }.assert(state => assertTrue(state == Published)),
    ),
    section("Cancel from any review leaf")(
      md"""
`all[InReview] via CancelReview to Draft` means `UnderReview` cancels the same way
`PendingReview` would:
""",
      exampleZIO {
        ZIO.scoped {
          for
            fsm   <- machine.start(Draft)
            _     <- fsm.send(SubmitForReview)
            _     <- fsm.send(AssignReviewer)
            _     <- fsm.send(CancelReview)
            state <- fsm.currentState
          yield state
        }.asDoc
      }.assert(state => assertTrue(state == Draft)),
      md"""
Next: [Heartbeat](heartbeat.html) for producing + timeouts, or
[Defining FSMs](defining-fsms.html) for `@@ Aspect.overriding`.
""",
    ),
  )
end DocumentWorkflow
