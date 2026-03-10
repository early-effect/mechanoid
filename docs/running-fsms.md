# Running FSMs

[Back to Documentation Index](DOCUMENTATION.md)

---

Mechanoid provides a unified `FSMRuntime[Id, S, E]` interface for all FSM execution scenarios. The runtime has three type parameters:

- `Id` - The instance identifier type (`Unit` for simple FSMs, or a custom type like `String` or `UUID` for persistent FSMs)
- `S` - The state type (sealed enum or sealed trait)
- `E` - The event type (sealed enum or sealed trait)

## Simple Runtime

For simple, single-instance FSMs without persistence, use `machine.start(initialState)`:

```scala
import mechanoid.*
import zio.*

enum MyState derives Finite:
  case Initial, Running, Done

enum MyEvent derives Finite:
  case Start, Finish

import MyState.*, MyEvent.*

val machine = Machine(assembly[MyState, MyEvent](
  Initial via Start to Running,
  Running via Finish to Done,
))
```

```scala
val program = ZIO.scoped {
  for
    fsm <- machine.start(Initial)  // Returns FSMRuntime[Unit, S, E]
    // Use the FSM...
    _ <- fsm.send(Start)
  yield ()
}
```

This creates an in-memory FSM with `Unit` as the instance ID. The FSM is automatically stopped when the scope closes.

## Persistent Runtime

For persistent, identified FSMs, use `FSMRuntime.apply`:

```scala
import mechanoid.*
import zio.*

enum OrderState derives Finite:
  case Pending, Paid, Shipped

enum OrderEvent derives Finite:
  case Pay, Ship

import OrderState.*, OrderEvent.*

val machine = Machine(assembly[OrderState, OrderEvent](
  Pending via Pay to Paid,
  Paid via Ship to Shipped,
))

type OrderId = String
val orderId: OrderId = "order-1"
val eventStoreLayer: zio.ULayer[EventStore[OrderId, OrderState, OrderEvent]] =
  InMemoryEventStore.layer[OrderId, OrderState, OrderEvent]
```

```scala
val program = ZIO.scoped {
  for
    fsm <- FSMRuntime(orderId, machine, Pending)  // Returns FSMRuntime[String, S, E]
    // Use the FSM...
    _ <- fsm.send(Pay)
  yield ()
}.provide(
  eventStoreLayer,
  TimeoutStrategy.fiber[OrderId],      // or TimeoutStrategy.durable for persistence
  LockingStrategy.optimistic[OrderId]  // or LockingStrategy.distributed for locking
)
```

The persistent runtime requires three dependencies in the environment:
- **`EventStore[Id, S, E]`** - Persists events and snapshots
- **`TimeoutStrategy[Id]`** - Handles state timeouts (fiber-based or durable)
- **`LockingStrategy[Id]`** - Handles concurrent access (optimistic or distributed)

## Sending Events

```scala
// Using the machine already defined above
val program = ZIO.scoped {
  for
    fsm     <- machine.start(Pending)
    outcome <- fsm.send(Pay)
  yield outcome.result match
    case TransitionResult.Goto(newState) => s"Transitioned to $newState"
    case TransitionResult.Stay           => "Stayed in current state"
    case TransitionResult.Stop(reason)   => s"Stopped: $reason"
}
```

Possible errors:
- `InvalidTransitionError` - No transition defined for state/event

---

[<< Previous: Defining FSMs](defining-fsms.md) | [Back to Index](DOCUMENTATION.md) | [Next: Persistence >>](persistence.md)
