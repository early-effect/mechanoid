package mechanoid.docs

import mechanoid.docs.DocZIO.*
import mechanoid.*
import specular.*
import zio.*
import zio.test.*

object RunningFsms extends MechanoidDocSpecSuite:

  enum MyState derives Finite:
    case Initial, Running, Done

  enum MyEvent derives Finite:
    case Start, Finish

  import MyState.*, MyEvent.*

  val machine = Machine(
    assembly[MyState, MyEvent](
      Initial via Start to Running,
      Running via Finish to Done,
    )
  )

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

  type OrderId = String

  def doc = page("Running FSMs")(
    md"""
`FSMRuntime[Id, S, E]` is the unified execution surface:

- `Id` — instance id (`Unit` for simple FSMs, or `String` / `UUID` when persisted)
- `S` / `E` — state and event types
""",
    section("Simple runtime")(
      md"""
`machine.start(initialState)` creates an in-memory runtime. The FSM stops when the scope closes.
""",
      exampleZIO {
        ZIO.scoped {
          for
            fsm     <- machine.start(Initial)
            outcome <- fsm.send(Start)
            state   <- fsm.currentState
          yield (outcome.result, state)
        }.asDoc
      }.assert { case (result, state) =>
        assertTrue(result == TransitionResult.Goto(Running)) && assertTrue(state == Running)
      },
    ),
    section("Persistent runtime")(
      md"""
`FSMRuntime(id, machine, initial)` needs three environment services:

| Service | Role |
|---------|------|
| `EventStore[Id, S, E]` | Events and snapshots |
| `TimeoutStrategy[Id]` | Fiber or durable timeouts |
| `LockingStrategy[Id]` | Optimistic or distributed locking |

```scala
FSMRuntime(orderId, machine, Pending).provide(
  InMemoryEventStore.layer,
  TimeoutStrategy.fiber[OrderId],
  LockingStrategy.optimistic[OrderId],
)
```
""",
      exampleZIO {
        val orderId: OrderId = "order-1"
        ZIO
          .scoped {
            for
              fsm   <- FSMRuntime(orderId, orderMachine, Pending)
              _     <- fsm.send(Pay)
              state <- fsm.currentState
            yield state
          }
          .provide(
            InMemoryEventStore.layer[OrderId, OrderState, OrderEvent],
            TimeoutStrategy.fiber[OrderId],
            LockingStrategy.optimistic[OrderId],
          )
          .asDoc
      }.assert(state => assertTrue(state == Paid)),
      md"""
`send` returns a transition outcome. Missing transitions raise `InvalidTransitionError`.

Next: [Persistence](persistence.html).
""",
    ),
  )
end RunningFsms
