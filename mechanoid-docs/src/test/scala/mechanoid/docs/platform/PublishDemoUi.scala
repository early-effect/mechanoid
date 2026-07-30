package mechanoid.docs.platform

import ascent.*
import ascent.dsl.*
import ascent.domtypes.{AttrKey, Codec}
import ascent.squawk.Squawk
import mermoid.ascent.MermoidAscent
import mermoid.{DiagramLayout, MermaidParser, Viewport}
import mechanoid.*
import specular.mermoid.Mermoid
import zio.*
import zio.json.*

import scala.language.implicitConversions

/** Multi-role publishing workflow demo (Document Workflow machine).
  *
  * Role panels gate actions by whether `(currentState, event)` is in the machine's transition map. `Reset` is a normal
  * event that returns any non-Draft leaf to Draft (same path as Pay/Ship: `send` + peer reconstruct).
  *
  * UI state is typed [[DocumentState]]; string conversion only happens at the mermoid node-id boundary.
  */
object PublishDemoUi:

  sealed trait DocumentState derives Finite, JsonCodec
  case object Draft extends DocumentState

  sealed trait InReview        extends DocumentState derives Finite, JsonCodec
  case object PendingReview    extends InReview
  case object UnderReview      extends InReview
  case object ChangesRequested extends InReview

  sealed trait Approval       extends DocumentState derives Finite, JsonCodec
  case object PendingApproval extends Approval
  case object Rejected        extends Approval

  case object Published extends DocumentState
  case object Archived  extends DocumentState
  case object Cancelled extends DocumentState

  enum DocumentEvent derives Finite, JsonCodec:
    case SubmitForReview, AssignReviewer, RequestChanges, ResubmitAfterChanges
    case ApproveReview, ApprovePublication, RejectPublication, Archive
    case CancelReview, Abandon, Reset

  import DocumentEvent.*

  val InstanceId = "docs-publish-1"

  val machine: Machine[DocumentState, DocumentEvent] = Machine(
    assembly[DocumentState, DocumentEvent](
      all[InReview] via CancelReview to Draft,
      all[InReview] via Reset to Draft,
      all[Approval] via Abandon to Cancelled,
      all[Approval] via Reset to Draft,
      Published via Reset to Draft,
      Archived via Reset to Draft,
      Cancelled via Reset to Draft,
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

  private val mermaidSource: String =
    MermaidVisualizer.flowchart(machine)

  private val parsed =
    MermaidParser.parse(mermaidSource) match
      case Right(d)  => d
      case Left(err) => throw new IllegalArgumentException(s"publish demo diagram: $err")

  private val testId   = AttrKey("data-testid", Codec.StringAsIs)
  private val roleAttr = AttrKey("data-role", Codec.StringAsIs)

  def canFire(from: DocumentState, event: DocumentEvent): Boolean =
    machine.transitions.contains(
      (machine.stateEnum.caseHash(from), machine.eventEnum.caseHash(event))
    )

  /** First event that moves `from` to the clicked diagram node name, if any. */
  def eventTo(from: DocumentState, targetName: String): Option[DocumentEvent] =
    val fromHash = machine.stateEnum.caseHash(from)
    machine.transitionMeta
      .find { meta =>
        meta.fromStateCaseHash == fromHash &&
        meta.targetStateCaseHash.exists(th => machine.stateEnum.nameFor(th) == targetName)
      }
      .flatMap { meta =>
        DocumentEvent.values.find(e => machine.eventEnum.caseHash(e) == meta.eventCaseHash)
      }
  end eventTo

  def panel(
      state: Squawk[DocumentState],
      note: ascent.ast.UI[Any],
      send: DocumentEvent => UIO[Unit],
  ): UIO[ascent.ast.UI[Any]] =
    for width <- sq(720.0)
    yield
      val diagram = Squawk.zipWith(width, state) { (w, sel) =>
        val scene = DiagramLayout.scene(parsed, Mermoid.chalkboard, Some(Viewport(w)))
        MermoidAscent.fromScene(
          scene,
          selected = Some(sel.toString),
          onSelect = id =>
            state.get.flatMap { cur =>
              eventTo(cur, id) match
                case Some(ev) => send(ev)
                case None     => ZIO.unit
            },
          containerWidth = Some(w),
        )
      }

      def action(
          role: String,
          event: DocumentEvent,
          label: String,
      ): ascent.ast.UI[Any] =
        E.button(
          testId(s"$role-${event.toString}"),
          A.disabled(state.map(s => !canFire(s, event))),
          Events.onClick(_ => send(event)),
          label,
        )

      E.div(
        A.className("mechanoid-multitab-demo mechanoid-publish-demo"),
        note,
        E.div(
          A.className("mermoid-ascent mechanoid-live-fsm"),
          E.div(
            A.className("mermoid-controls"),
            E.button(A.`type`("button"), Events.onClick(_ => width.set(480.0)), "Narrow"),
            E.button(A.`type`("button"), Events.onClick(_ => width.set(720.0)), "Medium"),
            E.button(A.`type`("button"), Events.onClick(_ => width.set(960.0)), "Wide"),
            E.span(A.className("mermoid-width-label"), width.map(w => s"viewport ${w.toInt}px")),
          ),
          diagram,
        ),
        E.p(
          A.className("mechanoid-live-status"),
          "Document: ",
          E.strong(testId("publish-state"), state.map(_.toString)),
          " · gated role actions below (disabled when illegal)",
        ),
        E.div(
          A.className("mechanoid-role-grid"),
          roleCard(
            "Writer",
            "Author the draft and respond to review",
            "writer",
            action("writer", SubmitForReview, "Submit for review"),
            action("writer", ResubmitAfterChanges, "Resubmit after changes"),
            action("writer", CancelReview, "Cancel review → Draft"),
          ),
          roleCard(
            "Reviewer",
            "Assign, request changes, or approve the review",
            "reviewer",
            action("reviewer", AssignReviewer, "Assign reviewer"),
            action("reviewer", RequestChanges, "Request changes"),
            action("reviewer", ApproveReview, "Approve review"),
          ),
          roleCard(
            "Approver",
            "Publication gate",
            "approver",
            action("approver", ApprovePublication, "Approve publication"),
            action("approver", RejectPublication, "Reject publication"),
            action("approver", Abandon, "Abandon → Cancelled"),
          ),
          roleCard(
            "Ops",
            "Archive or reset the document",
            "ops",
            action("ops", Archive, "Archive"),
            action("ops", Reset, "Reset to Draft"),
          ),
        ),
      )

  private def roleCard(
      title: String,
      blurb: String,
      roleId: String,
      actions: ascent.ast.UI[Any]*
  ): ascent.ast.UI[Any] =
    E.section(
      A.className("mechanoid-role-card"),
      roleAttr(roleId),
      E.h3(title),
      E.p(A.className("mechanoid-role-blurb"), blurb),
      E.div(
        A.className("mechanoid-live-actions"),
        ascent.ast.UI.Fragment(actions.toVector),
      ),
    )
end PublishDemoUi
