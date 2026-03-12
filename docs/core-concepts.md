# Core Concepts

[Back to Documentation Index](DOCUMENTATION.md)

---

## States

States represent the possible conditions of your FSM. Define them as plain Scala 3 enums:

```scala
import mechanoid.*
import zio.*
```

```scala
enum TrafficLight:
  case Red, Yellow, Green
```

States can also carry data (rich states):

```scala
enum OrderState:
  case Pending
  case Paid(transactionId: String)
  case Failed(reason: String)
```

When defining transitions, the state's "shape" (which case it is) is used for matching, not the exact value. This means a transition from `Failed` will match ANY `Failed(_)` state.

### Hierarchical States

For complex domains, organize related states using sealed traits:

```scala
sealed trait OrderState

case object Created extends OrderState

// Group all processing-related states
sealed trait Processing extends OrderState
case object ValidatingPayment extends Processing
case object ChargingCard extends Processing

case object Completed extends OrderState
```

Benefits:
- **Code organization** - Related states are grouped together
- **Group transitions** - Use `all[Processing]` to define transitions for all processing states at once
- **Type safety** - Can pattern match on parent traits

### Multi-State Transitions

Use `all[T]` to define transitions that apply to all subtypes of a sealed type:

```scala
// Setup for multi-state transition examples
sealed trait OrderState derives Finite

case object Created extends OrderState
case object Cancelled extends OrderState
case object Archived extends OrderState

sealed trait Processing extends OrderState derives Finite
case object ValidatingPayment extends Processing
case object ChargingCard extends Processing

case object Completed extends OrderState

enum OrderEvent derives Finite:
  case Cancel, Archive
```

```scala
import OrderEvent.*

// All Processing states can be cancelled
val transitions = assembly[OrderState, OrderEvent](
  all[Processing] via Cancel to Cancelled,
)
```

Use `anyOf(...)` for specific states that don't share a common parent:

```scala
import OrderEvent.*

// These specific states can be archived
val transitions = assembly[OrderState, OrderEvent](
  anyOf(Created, Completed) via Archive to Archived,
)
```

## Events

Events trigger transitions between states. Define them as plain enums:

```scala
import mechanoid.*
import zio.*
```

```scala
enum TrafficEvent:
  case Timer, EmergencyOverride
```

**Events with data:**

```scala
enum PaymentEvent:
  case Pay(amount: BigDecimal)
  case Refund(orderId: String, amount: BigDecimal)
```

**Event hierarchies:**

Like states, events can be organized hierarchically:

```scala
sealed trait UserEvent
sealed trait InputEvent extends UserEvent
case object Click extends InputEvent
case object Tap extends InputEvent
case object Swipe extends InputEvent
```

### Multi-Event Transitions

Use `event[T]` to match events by type (useful for events with data):

```scala
enum OrderState derives Finite:
  case Pending, Processing

enum OrderEvent derives Finite:
  case Pay(amount: BigDecimal)

import OrderState.*, OrderEvent.*
```

```scala
// Match any Pay event, regardless of amount
val transitions = assembly[OrderState, OrderEvent](
  Pending via event[Pay] to Processing,
)
```

Use `anyOfEvents(...)` for specific events:

```scala
import mechanoid.*
import zio.*

enum UIState derives Finite:
  case Idle, Active

sealed trait UIEvent derives Finite
case object Click extends UIEvent
case object Tap extends UIEvent
case object Swipe extends UIEvent

import UIState.*
```

```scala
// Multiple events trigger same transition
val transitions = assembly[UIState, UIEvent](
  Idle viaAnyOf anyOfEvents(Click, Tap, Swipe) to Active,
)
```

## Transitions

Transitions define what happens when an event is received in a specific state. Use the clean infix syntax:

```scala
import mechanoid.*
import zio.*

enum MyState derives Finite:
  case Pending, Paid, Failed

enum MyEvent derives Finite:
  case Pay, Heartbeat, Shutdown

import MyState.*, MyEvent.*
```

```scala
val transitions = assembly[MyState, MyEvent](
  // Simple transition: Pending + Pay -> Paid
  Pending via Pay to Paid,

  // Stay in current state
  Pending via Heartbeat to stay,

  // Stop the FSM
  Failed via Shutdown to stop,
)
```

**TransitionResult** represents the outcome:

| Result | Description |
|--------|-------------|
| `Goto(state)` | Transition to a new state |
| `Stay` | Remain in current state |
| `Stop(reason)` | Terminate the FSM |

## FSM State Container

`FSMState[S]` holds runtime information about the FSM:

```scala
val machine = Machine(assembly[MyState, MyEvent](
  Pending via Pay to Paid,
))

val program = ZIO.scoped {
  for
    fsm <- machine.start(Pending)
    s   <- fsm.state
  yield {
    s.current            // Current state
    s.history            // List of previous states (most recent first)
    s.stateData          // Arbitrary key-value data
    s.startedAt          // When FSM was created
    s.lastTransitionAt   // When last transition occurred
    s.transitionCount    // Number of transitions
    s.previousState      // Option of previous state
  }
}
```

---

[<< Previous: Overview](overview.md) | [Back to Index](DOCUMENTATION.md) | [Next: Defining FSMs >>](defining-fsms.md)
