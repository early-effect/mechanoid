package mechanoid.examples.hierarchical

import mechanoid.*
import zio.*

// ============================================
// Document Workflow - Hierarchical States
// ============================================

/** Document lifecycle states using hierarchical organization.
  *
  * This demonstrates how to organize related states using nested sealed traits. The hierarchy enables:
  *   - Clear visual grouping of related states
  *   - Type-safe state categories (e.g., `InReview` type for all review states)
  *   - Using `all[ParentState]` to define transitions for all states in a group
  *
  * Hierarchy:
  * {{{
  *   DocumentState
  *   ├── Draft                    (leaf - initial state)
  *   ├── InReview                 (parent - groups review states)
  *   │   ├── PendingReview        (leaf)
  *   │   ├── UnderReview          (leaf)
  *   │   └── ChangesRequested     (leaf)
  *   ├── Approval                 (parent - groups approval states)
  *   │   ├── PendingApproval      (leaf)
  *   │   └── Rejected             (leaf)
  *   ├── Published                (leaf - final state)
  *   ├── Archived                 (leaf - final state)
  *   └── Cancelled                (leaf - cancelled state)
  * }}}
  *
  * The `all[T]` matcher allows defining transitions that apply to all leaf states under a parent:
  * {{{
  * // Cancel from any InReview state goes back to Draft
  * all[InReview] via CancelReview to Draft,
  *
  * // Abandon from any Approval state goes to Cancelled
  * all[Approval] via Abandon to Cancelled,
  * }}}
  *
  * Leaf-level transitions can override parent-level ones using `@@ Aspect.overriding`.
  */
sealed trait DocumentState

// Initial state - document being drafted
case object Draft extends DocumentState

// ---- Review Phase States (grouped under InReview) ----
sealed trait InReview extends DocumentState

/** Waiting to be picked up by a reviewer. */
case object PendingReview extends InReview

/** Currently being reviewed. */
case object UnderReview extends InReview

/** Reviewer requested changes - needs revision. */
case object ChangesRequested extends InReview

// ---- Approval Phase States (grouped under Approval) ----
sealed trait Approval extends DocumentState

/** Waiting for final approval. */
case object PendingApproval extends Approval

/** Rejected by approver - needs to go back to review. */
case object Rejected extends Approval

// ---- Final States ----
/** Document published and visible. */
case object Published extends DocumentState

/** Document archived (no longer active). */
case object Archived extends DocumentState

/** Document cancelled - workflow terminated. */
case object Cancelled extends DocumentState

// ============================================
// Document Workflow Events
// ============================================

/** Events that drive document workflow transitions. */
sealed trait DocumentEvent

case object SubmitForReview      extends DocumentEvent
case object AssignReviewer       extends DocumentEvent
case object RequestChanges       extends DocumentEvent
case object ResubmitAfterChanges extends DocumentEvent
case object ApproveReview        extends DocumentEvent
case object ApprovePublication   extends DocumentEvent
case object RejectPublication    extends DocumentEvent
case object Publish              extends DocumentEvent
case object Archive              extends DocumentEvent
case object CancelReview         extends DocumentEvent // Cancel from any review state
case object Abandon              extends DocumentEvent // Abandon from any approval state

// ============================================
// Document Workflow FSM Definition
// ============================================

object DocumentWorkflowFSM:

  private def entered(state: DocumentState): (DocumentEvent, DocumentState) => UIO[Unit] =
    (event, _) => ZIO.logInfo(s"$event → $state")

  /** Hierarchical document workflow with entry logging on each transition. */
  val definition = Machine(
    assembly[DocumentState, DocumentEvent](
      (all[InReview] via CancelReview to Draft).onEntry(entered(Draft)),
      (all[Approval] via Abandon to Cancelled).onEntry(entered(Cancelled)),
    ) ++ assembly[DocumentState, DocumentEvent](
      (Draft via SubmitForReview to PendingReview).onEntry(entered(PendingReview)),
      (PendingReview via AssignReviewer to UnderReview).onEntry(entered(UnderReview)),
      (UnderReview via RequestChanges to ChangesRequested).onEntry(entered(ChangesRequested)),
      (UnderReview via ApproveReview to PendingApproval).onEntry(entered(PendingApproval)),
      (ChangesRequested via ResubmitAfterChanges to PendingReview).onEntry(entered(PendingReview)),
      (PendingApproval via ApprovePublication to Published).onEntry(entered(Published)),
      (PendingApproval via RejectPublication to Rejected).onEntry(entered(Rejected)),
      (Rejected via SubmitForReview to PendingReview).onEntry(entered(PendingReview)),
      (Published via Archive to Archived).onEntry(entered(Archived)),
    )
  )

end DocumentWorkflowFSM
