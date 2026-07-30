package mechanoid.docs

import mechanoid.docs.DocZIO.*
import mechanoid.*
import specular.*
import zio.*
import zio.test.*

object DistributedCoordination extends MechanoidDocSpecSuite:

  enum OrderState derives Finite:
    case Pending, Paid, Shipped

  enum OrderEvent derives Finite:
    case Pay, Ship

  import OrderState.*, OrderEvent.*

  val machine = Machine(
    assembly[OrderState, OrderEvent](
      Pending via Pay to Paid,
      Paid via Ship to Shipped,
    )
  )

  type OrderId = String

  def doc = page("Distributed Coordination")(
    section("Database as source of truth")(
      md"""
Mechanoid prefers a **load-on-demand** model over in-memory cluster gossip:

- State lives in the EventStore
- Application nodes stay stateless: any node can handle any instance
- Fresh loads see the latest events; no pub/sub membership protocol

```mermaid
flowchart LR
  NodeA[Node A] -->|load / append| DB[(EventStore)]
  NodeB[Node B] -->|load / append| DB
  class NodeA,NodeB,DB happy
```

Optimistic sequence numbers always detect write conflicts. Distributed locking prevents them.
"""
    ),
    section("LockingStrategy")(
      md"""
| Strategy | Behavior |
|----------|----------|
| `LockingStrategy.optimistic[Id]` | Detect `SequenceConflictError` at append |
| `LockingStrategy.distributed[Id]` | Acquire `FSMInstanceLock` before each transition |
""",
      exampleZIO {
        val orderId: OrderId = "order-lock-1"
        ZIO.scoped {
          for
            fsm   <- FSMRuntime(orderId, machine, Pending)
            _     <- fsm.send(Pay)
            state <- fsm.currentState
          yield state
        }.provide(
          InMemoryEventStore.layer[OrderId, OrderState, OrderEvent],
          ZLayer.fromZIO(InMemoryFSMInstanceLock.make[OrderId]),
          TimeoutStrategy.fiber[OrderId],
          LockingStrategy.distributed[OrderId],
        ).asDoc
      }.assert(state => assertTrue(state == Paid)),
    ),
    section("Lock heartbeat and atomic transitions")(
      md"""
```mermaid
flowchart LR
  Acquire[Acquire lock] --> Heartbeat[Heartbeat renew]
  Heartbeat --> Work[Process transitions]
  Work --> Release[Release]
  Heartbeat -.->|renewal fails| Lost[FailFast or Continue]
  class Acquire,Heartbeat,Work,Release happy
  class Lost warn
```

`withLockAndHeartbeat` renews the lock while long work runs (`LockHeartbeatConfig`:
`renewalInterval`, `renewalDuration`, `jitterFactor`, `onLockLost`).

`LockedFSMRuntime.withAtomicTransitions` holds one lock across a multi-step sequence so
validation → approval style flows stay exclusive.

Combine durable timeouts + distributed locking for production multi-node setups:

```scala
.provide(
  eventStoreLayer,
  timeoutStoreLayer,
  lockServiceLayer,
  TimeoutStrategy.durable[OrderId],
  LockingStrategy.distributed[OrderId],
)
```

Next: [Visualization](visualization.html).
"""
    ),
  )
end DistributedCoordination
