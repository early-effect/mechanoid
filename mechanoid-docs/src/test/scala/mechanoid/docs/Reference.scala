package mechanoid.docs

import mechanoid.docs.DocZIO.*
import mechanoid.*
import specular.*
import specular.mermoid.Mermoid
import zio.*
import zio.test.*

object Reference extends MechanoidDocSpecSuite:

  type OrderId = String

  def doc = page("Reference")(
    section("Matchers and targets")(
      md"""
| Construct | Role |
|-----------|------|
| `all[T]` | Every leaf under parent `T` |
| `anyOf(s1, …)` | Explicit state list |
| `state[S]` / `event[E]` | Match by type (payload cases) |
| `viaAnyOf` / `anyOfEvents` / `viaAll` | Multi-event edges |
| `stay` / `stop` / `stop("reason")` | Self-loop or terminal |
"""
    ),
    section("Aspects and effects")(
      md"""
| Construct | Role |
|-----------|------|
| `@@ Aspect.timeout(d, e)` | Schedule timeout event on entry to target |
| `@@ Aspect.overriding` | Intentional duplicate; last wins |
| `.onEntry` | Sync effect during `send` (failure → `ActionFailedError`) |
| `.producing` | Fork effect that returns another event |
| `.onEnter` / `.onExit` on `Assembly` | Per-state lifecycle hooks |
"""
    ),
    section("Runtime layers")(
      md"""
| Service | Common layers |
|---------|----------------|
| `EventStore` | `InMemoryEventStore.layer`, `mechanoid-postgres` |
| `TimeoutStrategy` | `fiber[Id]`, `durable[Id]` (+ `TimeoutStore`) |
| `LockingStrategy` | `optimistic[Id]`, `distributed[Id]` (+ `FSMInstanceLock`) |
"""
    ),
    section("Errors")(
      md"""
| Error | When |
|-------|------|
| `InvalidTransitionError` | No transition for state/event |
| `FSMStoppedError` | FSM already stopped |
| `ProcessingTimeoutError` | Timeout during event processing |
| `ActionFailedError` | Entry / lifecycle action failed |
| `PersistenceError` | Store operation failed |
| `SequenceConflictError` | Concurrent modification at append |
| `EventReplayError` | Stored event does not match definition |
| `LockingError` | Distributed lock busy / timeout |
"""
    ),
    section("Compact machine")(
      md"""
Timeout, cancel-from-many, and a durable timeout layer in one path:
""",
      example {
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

        Mermoid.diagram(
          MermaidVisualizer.stateDiagram(orderMachine, Some(Pending)),
          Mermoid.chalkboard,
        )
      }.assert(ui => assertTrue(ui.toString.nonEmpty)),
      exampleZIO {
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
      }.assert(state => assertTrue(state.toString == "Shipped")),
      md"""
Dependencies: Scala 3, ZIO 2 (provided), optional `mechanoid-postgres`.

Next: [Examples](examples.html).
""",
    ),
  )
end Reference
