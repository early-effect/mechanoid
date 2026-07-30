package mechanoid.examples.hierarchical

import zio.*
import mechanoid.*

/** Thin driver for the hierarchical document workflow.
  *
  * Hierarchy, DSL notes, and diagrams live in scaladoc / Specular docs (code fences). Transition results are logged
  * from `.onEntry` on [[DocumentWorkflowFSM.definition]].
  *
  * Run with: `sbt "examples/runMain mechanoid.examples.hierarchical.DocumentWorkflowApp"`
  */
object DocumentWorkflowApp extends ZIOAppDefault:

  private def happyPath: ZIO[Scope, MechanoidError, List[DocumentState]] =
    for
      fsm     <- DocumentWorkflowFSM.definition.start(Draft)
      _       <- fsm.send(SubmitForReview)
      _       <- fsm.send(AssignReviewer)
      _       <- fsm.send(ApproveReview)
      _       <- fsm.send(ApprovePublication)
      _       <- fsm.send(Archive)
      history <- fsm.history
    yield history

  private def rejectionPath: ZIO[Scope, MechanoidError, Unit] =
    for
      fsm <- DocumentWorkflowFSM.definition.start(Draft)
      _   <- fsm.send(SubmitForReview)
      _   <- fsm.send(AssignReviewer)
      _   <- fsm.send(ApproveReview)
      _   <- fsm.send(RejectPublication)
      _   <- fsm.send(SubmitForReview)
    yield ()

  private def cancelFromReviewPath: ZIO[Scope, MechanoidError, Unit] =
    for
      fsm <- DocumentWorkflowFSM.definition.start(Draft)
      _   <- fsm.send(SubmitForReview)
      _   <- fsm.send(AssignReviewer)
      _   <- fsm.send(CancelReview)
    yield ()

  private def changesRequestedPath: ZIO[Scope, MechanoidError, Unit] =
    for
      fsm <- DocumentWorkflowFSM.definition.start(Draft)
      _   <- fsm.send(SubmitForReview)
      _   <- fsm.send(AssignReviewer)
      _   <- fsm.send(RequestChanges)
      _   <- fsm.send(ResubmitAfterChanges)
    yield ()

  override def run: ZIO[Any, Any, Unit] =
    for
      _       <- ZIO.logInfo("Document workflow demo (hierarchical states)")
      history <- ZIO.scoped(happyPath)
      _       <- ZIO.logInfo(s"Happy path history (newest first): $history")
      _       <- ZIO.logInfo("Rejection path")
      _       <- ZIO.scoped(rejectionPath)
      _       <- ZIO.logInfo("Cancel from review (all[InReview])")
      _       <- ZIO.scoped(cancelFromReviewPath)
      _       <- ZIO.logInfo("Changes requested path")
      _       <- ZIO.scoped(changesRequestedPath)
      _       <- ZIO.logInfo("Demo complete")
    yield ()
end DocumentWorkflowApp
