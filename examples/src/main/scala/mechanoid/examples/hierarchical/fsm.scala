package mechanoid.examples.hierarchical

import mechanoid.*

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

  /** The document workflow FSM definition.
    *
    * This definition demonstrates the power of the suite-style DSL:
    *
    *   - **Readable syntax**: `State via Event to Target` reads like plain English
    *   - **Hierarchical matching**: `all[InReview]` matches ALL leaf states under a parent trait
    *   - **Override support**: Use `@@ Aspect.overriding` to override parent-level transitions
    *   - **Machine composition**: Reusable assemblies can be composed with `combine`/`++`
    *
    * The `all[T]` pattern is incredibly powerful for:
    *   - Cancel/abort actions that apply to multiple related states
    *   - Default behaviors for entire state groups
    *   - Reducing boilerplate in complex workflows
    *
    * Example showing the composition pattern:
    * {{{
    * // Compose assemblies with ++ for compile-time duplicate detection
    * val machine = Machine(
    *   assembly[DocumentState, DocumentEvent](
    *     all[InReview] via CancelReview to Draft,
    *     all[Approval] via Abandon to Cancelled,
    *   ) ++ assembly[DocumentState, DocumentEvent](
    *     Draft via SubmitForReview to PendingReview,
    *     // ... more transitions ...
    *   )
    * )
    * }}}
    */

  // ═══════════════════════════════════════════════════════════════════════════
  // Reusable machine fragment: Group behaviors using hierarchical matching
  // ═══════════════════════════════════════════════════════════════════════════

  /** The complete workflow FSM composed using `++`.
    *
    * Group behaviors (cancel/abandon from hierarchical state groups) are defined in one assembly, then composed with
    * specific transitions using `++`. Assembly composition provides full compile-time duplicate detection.
    *
    * Override semantics: Last transition wins. If a more specific transition needs to override a group behavior, use
    * `@@ Aspect.overriding`.
    */
  val definition = Machine(
    // Group behaviors: cancelable review states, abandonable approval states
    assembly[DocumentState, DocumentEvent](
      // Any state in the review phase can be cancelled, returning to Draft
      // This single line applies to: PendingReview, UnderReview, ChangesRequested
      all[InReview] via CancelReview to Draft,
      // Any state in the approval phase can be abandoned
      // This applies to: PendingApproval, Rejected
      all[Approval] via Abandon to Cancelled,
    ) ++ assembly[DocumentState, DocumentEvent](
      // ===== Draft Phase =====
      Draft via SubmitForReview to PendingReview,
      // ===== Review Phase (Individual transitions) =====
      PendingReview via AssignReviewer to UnderReview,
      UnderReview via RequestChanges to ChangesRequested,
      UnderReview via ApproveReview to PendingApproval,
      ChangesRequested via ResubmitAfterChanges to PendingReview,
      // ===== Approval Phase (Individual transitions) =====
      PendingApproval via ApprovePublication to Published,
      PendingApproval via RejectPublication to Rejected,
      Rejected via SubmitForReview to PendingReview,
      // ===== Final States =====
      Published via Archive to Archived,
    )
  )

end DocumentWorkflowFSM
