package mechanoid.docs

import mechanoid.docs.DocZIO.*
import mechanoid.*
import specular.*
import specular.mermoid.Mermoid
import zio.*
import zio.test.*

object SideEffects extends MechanoidDocSpecSuite:

  enum OrderState derives Finite:
    case Created, Processing, AwaitingResult, Succeeded, Failed

  enum OrderEvent derives Finite:
    case StartPayment
    case CheckPayment(orderId: String)
    case PaymentSucceeded(txnId: String)
    case PaymentFailed(message: String)

  import OrderState.*, OrderEvent.*

  case class PaymentStatus(success: Boolean, txnId: String, message: String)

  object paymentService:
    def checkStatus(orderId: String): UIO[PaymentStatus] =
      ZIO.succeed(PaymentStatus(true, "txn-123", ""))

  val producingMachine = Machine(
    assembly[OrderState, OrderEvent](
      (Processing via event[CheckPayment] to AwaitingResult)
        .producing { (event, _) =>
          event match
            case CheckPayment(orderId) =>
              paymentService.checkStatus(orderId).map {
                case PaymentStatus(true, txnId, _) => PaymentSucceeded(txnId)
                case PaymentStatus(false, _, msg)  => PaymentFailed(msg)
              }
            case _ => ZIO.succeed(PaymentFailed("unexpected event"))
        },
      AwaitingResult via event[PaymentSucceeded] to Succeeded,
      AwaitingResult via event[PaymentFailed] to Failed,
    )
  )

  def doc = page("Side Effects")(
    section("Synchronous entry effects")(
      md"""
Use `.onEntry` for effects that run during `send`. Failures become `ActionFailedError` and the
transition is **not** persisted. Good for logging, metrics, validation, and quick sync work.

Assemblies also support per-state `.onEnter(state)(…)` / `.onExit(state)(…)` for effects tied to
arriving in or leaving a state regardless of which edge was taken.
""",
      exampleZIO {
        ZIO.scoped {
          for
            log <- Ref.make(List.empty[String])
            machine = Machine(
              assembly[OrderState, OrderEvent](
                (Created via StartPayment to Processing)
                  .onEntry { (_, target) =>
                    log.update(s"entered $target" :: _)
                  }
              )
            )
            fsm   <- machine.start(Created)
            _     <- fsm.send(StartPayment)
            state <- fsm.currentState
            notes <- log.get
          yield (state, notes)
        }.asDoc
      }.assert { case (state, notes) =>
        assertTrue(state == Processing) &&
        assertTrue(notes == List("entered Processing"))
      },
    ),
    section("Producing effects")(
      md"""
Use `.producing` for async work that returns another event. The effect forks as a daemon; the
produced event is sent back to the FSM. Errors are logged and do not fail the original transition.
""",
      example {
        Mermoid.diagram(
          MermaidVisualizer.flowchart(producingMachine),
          DocsDiagrams.diagramConfig,
        )
      }.assert(ui => assertTrue(ui.toString.nonEmpty)),
      exampleZIO {
        ZIO.scoped {
          for
            fsm   <- producingMachine.start(Processing)
            _     <- fsm.send(CheckPayment("order-1"))
            _     <- ZIO.sleep(100.millis) // allow producing fiber to complete
            state <- fsm.currentState
          yield state
        }.asDoc
      }.assert(state => assertTrue(state == Succeeded)),
      md"""
Combine `.producing` with `@@ Aspect.timeout` for self-driving heartbeats (see the Heartbeat
domain page and `examples/heartbeat`).

Next: [Running FSMs](running-fsms.html).
""",
    ),
  )
end SideEffects
