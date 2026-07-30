package mechanoid.docs

import mechanoid.docs.DocZIO.*
import mechanoid.*
import specular.*
import specular.mermoid.Mermoid
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
`send` returns a transition outcome (`Goto`, `Stay`, or `Stop`). Missing transitions raise
`InvalidTransitionError`.
""",
      example {
        Mermoid.diagram(
          MermaidVisualizer.stateDiagram(machine, Some(Initial)),
          DocsDiagrams.diagramConfig,
        )
      }.assert(ui => assertTrue(ui.toString.nonEmpty)),
      exampleZIO {
        ZIO.scoped {
          for
            fsm     <- machine.start(Initial)
            outcome <- fsm.send(Start)
            state   <- fsm.currentState
            hist    <- fsm.history
          yield (outcome.result, state, hist.length)
        }.asDoc
      }.assert { case (result, state, histLen) =>
        assertTrue(result == TransitionResult.Goto(Running)) &&
        assertTrue(state == Running) &&
        assertTrue(histLen >= 1)
      },
      exampleZIO {
        ZIO.scoped {
          for
            fsm    <- machine.start(Initial)
            failed <- fsm.send(Finish).either
          yield failed
        }.asDoc
      }.assert { failed =>
        assertTrue(failed.isLeft) &&
        assertTrue(failed.swap.exists(_.isInstanceOf[InvalidTransitionError[?, ?]]))
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
Next: [Persistence](persistence.html) for recover-on-construct and snapshots.
""",
    ),
  )
end RunningFsms
