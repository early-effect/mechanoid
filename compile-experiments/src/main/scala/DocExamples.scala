package experiments

import mechanoid.*
import zio.*

/** Verify documentation examples compile.
  *
  * This file contains key code patterns from README.md and DOCUMENTATION.md to ensure they actually compile.
  *
  * Run with: sbt compile-experiments/compile
  */
object DocExamples:
  // ============================================
  // README Quick Start Example
  // ============================================

  enum OrderState derives Finite:
    case Pending, Paid, Shipped

  enum OrderEvent derives Finite:
    case Pay, Ship

  import OrderState.*, OrderEvent.*

  val orderMachine = Machine(
    assembly[OrderState, OrderEvent](
      Pending via Pay to Paid,
      Paid via Ship to Shipped,
    )
  )

  // Verify machine.start works (from README Quick Start)
  val quickStartProgram = ZIO.scoped {
    for
      fsm   <- orderMachine.start(Pending)
      _     <- fsm.send(Pay)
      _     <- fsm.send(Ship)
      state <- fsm.currentState
    yield state
  }

  // ============================================
  // Persistence Example (from README)
  // ============================================

  // Verify FSMRuntime with layers works
  def persistenceExample(orderId: String) = ZIO.scoped {
    for
      fsm <- FSMRuntime(orderId, orderMachine, Pending)
      _   <- fsm.send(Pay)
    yield ()
  }

  // ============================================
  // Durable Timeouts Example (from README)
  // NOTE: The docs show `State @@ Aspect.timeout(...)` but this is incorrect!
  // The correct syntax is `(A via E to B) @@ Aspect.timeout(duration, event)`
  // ============================================

  enum PaymentState derives Finite:
    case Pending, AwaitingPayment, Paid, Cancelled

  enum PaymentEvent derives Finite:
    case StartPayment, ConfirmPayment, PaymentTimeout

  import PaymentState.*, PaymentEvent.*

  // Correct timeout syntax: apply @@ to the transition, not the state
  val paymentMachine = Machine(
    assembly[PaymentState, PaymentEvent](
      (PaymentState.Pending via StartPayment to AwaitingPayment) @@ Aspect.timeout(30.minutes, PaymentTimeout),
      AwaitingPayment via ConfirmPayment to PaymentState.Paid,
      AwaitingPayment via PaymentTimeout to Cancelled,
    )
  )

  // ============================================
  // Hierarchical States Example (from DOCUMENTATION)
  // ============================================

  sealed trait DocumentState derives Finite
  case object Draft         extends DocumentState
  case object PendingReview extends DocumentState
  case object UnderReview   extends DocumentState
  case object Approved      extends DocumentState
  case object Cancelled     extends DocumentState

  // Processing states grouped under trait
  sealed trait InReview    extends DocumentState derives Finite
  case object ReviewPhase1 extends InReview
  case object ReviewPhase2 extends InReview

  sealed trait Approval       extends DocumentState derives Finite
  case object AwaitingSignoff extends Approval

  enum DocumentEvent derives Finite:
    case SubmitForReview, AssignReviewer, CancelReview, Abandon

  import DocumentEvent.*

  // Verify all[T] syntax for hierarchical states
  val cancelableBehaviors = assembly[DocumentState, DocumentEvent](
    all[InReview] via CancelReview to Draft,
    all[Approval] via Abandon to Cancelled,
  )

  // ============================================
  // Assembly Composition Example (from DOCUMENTATION)
  // ============================================

  val fullWorkflow = Machine(
    assembly[DocumentState, DocumentEvent](
      all[InReview] via CancelReview to Draft,
      all[Approval] via Abandon to Cancelled,
    ) ++ assembly[DocumentState, DocumentEvent](
      Draft via SubmitForReview to PendingReview,
      PendingReview via AssignReviewer to UnderReview,
    )
  )

  // ============================================
  enum SimpleState derives Finite:
    case Idle, Running

  enum SimpleEvent derives Finite:
    case Start, Stop

  import SimpleState.*, SimpleEvent.*

  // ============================================
  // onEntry Example (from DOCUMENTATION)
  // ============================================

  val machineWithOnEntry = Machine(
    assembly[SimpleState, SimpleEvent](
      (Idle via Start to Running)
        .onEntry { (event, targetState) =>
          ZIO.logInfo(s"Starting from event $event to state $targetState")
        },
      Running via Stop to Idle,
    )
  )

end DocExamples
