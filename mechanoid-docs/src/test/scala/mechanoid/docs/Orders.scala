package mechanoid.docs

import mechanoid.docs.DocZIO.*
import mechanoid.*
import specular.*
import specular.mermoid.Mermoid
import zio.*
import zio.test.*

/** Order / petstore slice (from `examples/petstore`). */
object Orders extends MechanoidDocSpecSuite:

  enum OrderState derives Finite:
    case Created, PaymentProcessing, Paid, ShippingRequested, Shipped, Cancelled

  enum OrderEvent derives Finite:
    case InitiatePayment(orderId: Int, amount: BigDecimal)
    case PaymentSucceeded(orderId: Int, txnId: String)
    case PaymentFailed(orderId: Int, reason: String)
    case RequestShipping(orderId: Int)
    case ShipmentDispatched(orderId: Int, trackingId: String)
    case PaymentTimeout
    case ShippingTimeout

  import OrderState.*, OrderEvent.*

  val machine = Machine(
    assembly[OrderState, OrderEvent](
      (Created via event[InitiatePayment] to PaymentProcessing)
        .onEntry { (e, _) =>
          e match
            case InitiatePayment(id, amount) =>
              ZIO.logInfo(s"order $id: charging $$$amount")
            case _ => ZIO.unit
        }
        .producing { (e, _) =>
          e match
            case InitiatePayment(id, _) =>
              ZIO.succeed(PaymentSucceeded(id, "txn-doc"))
            case _ => ZIO.succeed(PaymentFailed(0, "unexpected"))
        } @@ Aspect.timeout(5.minutes, PaymentTimeout),
      PaymentProcessing via event[PaymentSucceeded] to Paid,
      PaymentProcessing via event[PaymentFailed] to Cancelled,
      PaymentProcessing via PaymentTimeout to Cancelled,
      (Paid via event[RequestShipping] to ShippingRequested)
        @@ Aspect.timeout(1.hour, ShippingTimeout),
      ShippingRequested via event[ShipmentDispatched] to Shipped,
      (ShippingRequested via ShippingTimeout to ShippingRequested)
        .onEntry { (_, _) =>
          ZIO.logWarning("shipping timeout - escalate to ops")
        },
    )
  )

  private val machineSource =
    md"""
```scala
enum OrderState derives Finite:
  case Created, PaymentProcessing, Paid, ShippingRequested, Shipped, Cancelled

enum OrderEvent derives Finite:
  case InitiatePayment(orderId: Int, amount: BigDecimal)
  case PaymentSucceeded(orderId: Int, txnId: String)
  case PaymentFailed(orderId: Int, reason: String)
  case RequestShipping(orderId: Int)
  case ShipmentDispatched(orderId: Int, trackingId: String)
  case PaymentTimeout, ShippingTimeout

val machine = Machine(
  assembly[OrderState, OrderEvent](
    (Created via event[InitiatePayment] to PaymentProcessing)
      .onEntry { (e, _) => /* log */ ZIO.unit }
      .producing { (e, _) =>
        e match
          case InitiatePayment(id, _) => ZIO.succeed(PaymentSucceeded(id, "txn-doc"))
          case _                      => ZIO.succeed(PaymentFailed(0, "unexpected"))
      } @@ Aspect.timeout(5.minutes, PaymentTimeout),
    PaymentProcessing via event[PaymentSucceeded] to Paid,
    PaymentProcessing via event[PaymentFailed] to Cancelled,
    PaymentProcessing via PaymentTimeout to Cancelled,
    (Paid via event[RequestShipping] to ShippingRequested)
      @@ Aspect.timeout(1.hour, ShippingTimeout),
    ShippingRequested via event[ShipmentDispatched] to Shipped,
    (ShippingRequested via ShippingTimeout to ShippingRequested)
      .onEntry { (_, _) => ZIO.logWarning("shipping timeout - escalate to ops") },
  )
)
```
"""

  def doc = page("Orders")(
    md"""
A pet-store style order graph: rich events carry payloads, `event[T]` matches by type, and
timeouts either cancel payment or loop in shipping while ops is alerted.

Teaching slice of `examples/.../petstore`. Machine used below:
""",
    machineSource,
    section("The graph")(
      example {
        Mermoid.diagram(
          MermaidVisualizer.flowchart(machine),
          Mermoid.chalkboard,
        )
      }.assert(ui => assertTrue(ui.toString.nonEmpty)),
      md"""
Payment edges use `event[InitiatePayment]` / `event[PaymentSucceeded]` so runtime values carry
ids and amounts while the assembly stays declarative.
""",
    ),
    section("Pay then ship")(
      md"""
`InitiatePayment` enters `PaymentProcessing`, the producing effect emits `PaymentSucceeded`,
then shipping completes:
""",
      exampleZIO {
        ZIO.scoped {
          for
            fsm   <- machine.start(Created)
            _     <- fsm.send(InitiatePayment(1, BigDecimal(19.99)))
            _     <- ZIO.sleep(50.millis)
            _     <- fsm.send(RequestShipping(1))
            _     <- fsm.send(ShipmentDispatched(1, "trk-1"))
            state <- fsm.currentState
          yield state
        }.asDoc
      }.assert(state => assertTrue(state == Shipped)),
    ),
    section("Shipping timeout stays put")(
      md"""
An explicit self-transition (`to ShippingRequested`) keeps the FSM put while the entry effect
can escalate. The full petstore example writes the same edge as `to stay`.
""",
      exampleZIO {
        ZIO.scoped {
          for
            fsm     <- machine.start(Created)
            _       <- fsm.send(InitiatePayment(2, BigDecimal(5)))
            _       <- ZIO.sleep(50.millis)
            _       <- fsm.send(RequestShipping(2))
            outcome <- fsm.send(ShippingTimeout)
            state   <- fsm.currentState
          yield (outcome.result, state)
        }.asDoc
      }.assert { case (result, state) =>
        assertTrue(result == TransitionResult.Goto(ShippingRequested)) &&
        assertTrue(state == ShippingRequested)
      },
      md"""
Next: [Examples](examples.html) for repo mains, or [Testing](testing.html).
""",
    ),
  )
end Orders
