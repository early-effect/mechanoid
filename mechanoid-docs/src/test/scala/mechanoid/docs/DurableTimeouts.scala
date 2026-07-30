package mechanoid.docs

import mechanoid.docs.DocZIO.*
import mechanoid.*
import specular.*
import zio.*
import zio.test.*

object DurableTimeouts extends MechanoidDocSpecSuite:

  enum OrderState derives Finite:
    case Pending, Started, Done, Cancelled

  enum OrderEvent derives Finite:
    case StartPayment, Complete, PaymentTimeout

  import OrderState.*, OrderEvent.*

  val machine = Machine(
    assembly[OrderState, OrderEvent](
      (Pending via StartPayment to Started) @@ Aspect.timeout(1.hour, PaymentTimeout),
      Started via Complete to Done,
      Started via PaymentTimeout to Cancelled,
    )
  )

  type OrderId = String

  def doc = page("Durable Timeouts")(
    section("Why durable")(
      md"""
Fiber timeouts are fast and local. If the node dies while an FSM sits in a timed state, that
fiber is gone. Durable timeouts store deadlines in a `TimeoutStore` so another node's sweeper
can fire them.
""",
      md"""
```mermaid
flowchart LR
  NodeA[Node A schedules] --> Store[TimeoutStore]
  NodeA -.->|dies| Gone[Fiber gone]
  Store --> Sweeper[TimeoutSweeper]
  Sweeper --> NodeB[Node B fires timeout event]
  class NodeA,Store,Sweeper,NodeB happy
  class Gone warn
```
"""
    ),
    section("TimeoutStrategy")(
      md"""
| Strategy | Layer | Survives restart |
|----------|-------|------------------|
| Fiber | `TimeoutStrategy.fiber[Id]` | No |
| Durable | `TimeoutStrategy.durable[Id]` (+ `TimeoutStore`) | Yes |
""",
      exampleZIO {
        val orderId: OrderId = "order-timeout-1"
        ZIO.scoped {
          for
            fsm   <- FSMRuntime(orderId, machine, Pending)
            _     <- fsm.send(StartPayment)
            state <- fsm.currentState
          yield state
        }.provide(
          InMemoryEventStore.layer[OrderId, OrderState, OrderEvent],
          ZLayer.fromZIO(InMemoryTimeoutStore.make[OrderId]),
          TimeoutStrategy.durable[OrderId],
          LockingStrategy.optimistic[OrderId],
        ).asDoc
      }.assert(state => assertTrue(state == Started)),
    ),
    section("TimeoutSweeper")(
      md"""
A background sweeper:

1. Queries expired, unclaimed timeouts
2. Claims each timeout
3. Validates `(stateHash, sequenceNr)` so stale timeouts do not fire
4. Looks up the timeout event via `Machine.timeoutEvents` and `runtime.send`s it
5. Marks complete

Use `TimeoutSweeperConfig` for interval, jitter, batch size, claim duration, and `nodeId`.
Optional **leader election** via `LeaseStore` keeps a single active sweeper to reduce DB load.

See `examples/heartbeat` for a full sweeper alongside `FSMRuntime`.

Next: [Distributed Coordination](distributed-coordination.html).
"""
    ),
  )
end DurableTimeouts
