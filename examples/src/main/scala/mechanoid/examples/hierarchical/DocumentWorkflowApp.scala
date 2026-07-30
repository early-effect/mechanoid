package mechanoid.examples.hierarchical

import zio.*
import mechanoid.*

/** Demonstrates hierarchical state organization in Mechanoid FSMs.
  *
  * This example shows how to:
  *   1. Organize related states using nested sealed traits
  *   2. Use `all[ParentState]` for group transitions
  *   3. Define individual leaf state transitions
  *   4. Override group transitions for specific states if needed
  *
  * Run with: `sbt "examples/runMain mechanoid.examples.hierarchical.DocumentWorkflowApp"`
  */
object DocumentWorkflowApp extends ZIOAppDefault:

  private def printState(label: String, state: DocumentState): UIO[Unit] =
    Console.printLine(s"$label: $state").orDie

  private def happyPath: ZIO[Scope, MechanoidError, List[DocumentState]] =
    for
      fsm     <- DocumentWorkflowFSM.definition.start(Draft)
      _       <- Console.printLine("Initial: Draft").orDie
      _       <- fsm.send(SubmitForReview)
      s1      <- fsm.currentState
      _       <- printState("After SubmitForReview", s1)
      _       <- fsm.send(AssignReviewer)
      s2      <- fsm.currentState
      _       <- printState("After AssignReviewer", s2)
      _       <- fsm.send(ApproveReview)
      s3      <- fsm.currentState
      _       <- printState("After ApproveReview", s3)
      _       <- fsm.send(ApprovePublication)
      s4      <- fsm.currentState
      _       <- printState("After ApprovePublication", s4)
      _       <- fsm.send(Archive)
      s5      <- fsm.currentState
      _       <- printState("After Archive", s5)
      history <- fsm.history
    yield history

  private def rejectionPath: ZIO[Scope, MechanoidError, Unit] =
    for
      fsm <- DocumentWorkflowFSM.definition.start(Draft)
      _   <- Console.printLine("Initial: Draft").orDie
      _   <- fsm.send(SubmitForReview)
      _   <- fsm.send(AssignReviewer)
      _   <- fsm.send(ApproveReview)
      s1  <- fsm.currentState
      _   <- printState("After review approval", s1)
      _   <- fsm.send(RejectPublication)
      s2  <- fsm.currentState
      _   <- printState("After RejectPublication", s2)
      _   <- fsm.send(SubmitForReview)
      s3  <- fsm.currentState
      _   <- printState("After SubmitForReview (resubmit)", s3)
    yield ()

  private def cancelFromReviewPath: ZIO[Scope, MechanoidError, Unit] =
    for
      fsm <- DocumentWorkflowFSM.definition.start(Draft)
      _   <- Console.printLine("Initial: Draft").orDie
      _   <- fsm.send(SubmitForReview)
      _   <- fsm.send(AssignReviewer)
      s1  <- fsm.currentState
      _   <- printState("Under review", s1)
      _   <- fsm.send(CancelReview)
      s2  <- fsm.currentState
      _   <- printState("After CancelReview (group transition)", s2)
    yield ()

  private def changesRequestedPath: ZIO[Scope, MechanoidError, Unit] =
    for
      fsm <- DocumentWorkflowFSM.definition.start(Draft)
      _   <- Console.printLine("Initial: Draft").orDie
      _   <- fsm.send(SubmitForReview)
      _   <- fsm.send(AssignReviewer)
      s1  <- fsm.currentState
      _   <- printState("Under review", s1)
      _   <- fsm.send(RequestChanges)
      s2  <- fsm.currentState
      _   <- printState("After RequestChanges", s2)
      _   <- fsm.send(ResubmitAfterChanges)
      s3  <- fsm.currentState
      _   <- printState("After ResubmitAfterChanges", s3)
    yield ()

  override def run: ZIO[Any, Any, Unit] =
    for
      _      <- Console.printLine("=== Document Workflow Demo (Hierarchical States) ===")
      _      <- Console.printLine("")
      _      <- Console.printLine("State Hierarchy:")
      _      <- Console.printLine("  DocumentState")
      _      <- Console.printLine("  ├── Draft (initial)")
      _      <- Console.printLine("  ├── InReview (parent - all[InReview] matches these)")
      _      <- Console.printLine("  │   ├── PendingReview")
      _      <- Console.printLine("  │   ├── UnderReview")
      _      <- Console.printLine("  │   └── ChangesRequested")
      _      <- Console.printLine("  ├── Approval (parent - all[Approval] matches these)")
      _      <- Console.printLine("  │   ├── PendingApproval")
      _      <- Console.printLine("  │   └── Rejected")
      _      <- Console.printLine("  ├── Published (final)")
      _      <- Console.printLine("  ├── Archived (final)")
      _      <- Console.printLine("  └── Cancelled")
      _      <- Console.printLine("")
      _      <- Console.printLine("Mermaid State Diagram:")
      _      <- Console.printLine("```mermaid")
      _      <- Console.printLine(DocumentWorkflowFSM.definition.toMermaidStateDiagram)
      _      <- Console.printLine("```")
      _      <- Console.printLine("")
      _      <- Console.printLine("Running Happy Path Workflow:")
      _      <- Console.printLine("-" * 40)
      result <- ZIO.scoped(happyPath)
      _      <- Console.printLine("")
      _      <- Console.printLine("Full state history (most recent first):")
      _      <- ZIO.foreachDiscard(result)(s => Console.printLine(s"  - $s"))
      _      <- Console.printLine("")
      _      <- Console.printLine("=== Rejection Path Demo ===")
      _      <- Console.printLine("-" * 40)
      _      <- ZIO.scoped(rejectionPath)
      _      <- Console.printLine("")
      _      <- Console.printLine("=== Cancel from Review Phase Demo ===")
      _      <- Console.printLine("(Using all[InReview] via CancelReview to Draft)")
      _      <- Console.printLine("-" * 40)
      _      <- ZIO.scoped(cancelFromReviewPath)
      _      <- Console.printLine("")
      _      <- Console.printLine("=== Changes Requested Path Demo ===")
      _      <- Console.printLine("-" * 40)
      _      <- ZIO.scoped(changesRequestedPath)
      _      <- Console.printLine("")
      _      <- Console.printLine("Demo complete!")
    yield ()
end DocumentWorkflowApp
