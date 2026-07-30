package mechanoid.docs

import mechanoid.docs.DocZIO.*
import mechanoid.*
import specular.*
import zio.*
import zio.test.*

object Reference extends MechanoidDocSpecSuite:

  enum OrderState derives Finite:
    case Pending, AwaitingPayment, Paid, Shipped, Delivered, Cancelled

  enum OrderEvent derives Finite:
    case RequestPayment, ConfirmPayment, Ship, Deliver, Cancel, PaymentTimeout

  import OrderState.*, OrderEvent.*

  val orderMachine = Machine(
    assembly[OrderState, OrderEvent](
      (Pending via RequestPayment to AwaitingPayment) @@ Aspect.timeout(30.minutes, PaymentTimeout),
      AwaitingPayment via ConfirmPayment to Paid,
      Paid via Ship to Shipped,
      Shipped via Deliver to Delivered,
      AwaitingPayment via PaymentTimeout to Cancelled,
      anyOf(Pending, AwaitingPayment) via Cancel to Cancelled,
    )
  )

  type OrderId = String

  def doc = page("Reference")(
    section("Errors")(
      md"""
| Error | Cause |
|-------|-------|
| `InvalidTransitionError` | No transition for state/event |
| `FSMStoppedError` | FSM already stopped |
| `ProcessingTimeoutError` | Timeout during event processing |
| `ActionFailedError` | Entry / lifecycle action failed |
| `PersistenceError` | Store operation failed |
| `SequenceConflictError` | Concurrent modification |
| `EventReplayError` | Stored event does not match definition |
| `LockingError` | Distributed lock busy / timeout |
"""
    ),
    section("Complete path")(
      md"""
A compact machine with timeout, cancel from multiple states, and persistent runtime:
""",
      exampleZIO {
        val orderId: OrderId = "order-ref-1"
        ZIO
          .scoped {
            for
              fsm   <- FSMRuntime(orderId, orderMachine, Pending)
              _     <- fsm.send(RequestPayment)
              _     <- fsm.send(ConfirmPayment)
              _     <- fsm.send(Ship)
              state <- fsm.currentState
            yield state
          }
          .provide(
            InMemoryEventStore.layer[OrderId, OrderState, OrderEvent],
            ZLayer.fromZIO(InMemoryTimeoutStore.make[OrderId]),
            TimeoutStrategy.durable[OrderId],
            LockingStrategy.optimistic[OrderId],
          )
          .asDoc
      }.assert(state => assertTrue(state == Shipped)),
    ),
    section("Dependencies")(
      md"""
- Scala 3.x
- ZIO 2.x (provided)
- Optional: `mechanoid-postgres` (Saferis + PostgreSQL)

Key types: `assembly` / `assemblyAll`, `Machine`, `Assembly`, `FSMRuntime`,
`TimeoutStrategy`, `LockingStrategy`, `TimeoutSweeper`, `FSMInstanceLock`, `LeaderElection`.

Next: [Examples](examples.html).
"""
    ),
  )
end Reference
