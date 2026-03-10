# Overview

[Back to Documentation Index](DOCUMENTATION.md)

---

Mechanoid provides a declarative DSL for defining finite state machines with:

- **Type-safe states and events** using Scala 3 enums
- **Ergonomic infix syntax** - `State via Event to Target`
- **Composable assemblies** - build reusable FSM fragments and combine them with full compile-time validation
- **Effectful transitions** via ZIO
- **Optional persistence** through event sourcing
- **Durable timeouts** that survive node failures
- **Distributed coordination** with claim-based locking and leader election

```scala mdoc:silent
import mechanoid.*
import zio.*

// Define states and events as plain enums
enum OrderState derives Finite:
  case Pending, Paid, Shipped, Delivered

enum OrderEvent derives Finite:
  case Pay, Ship, Deliver

import OrderState.*, OrderEvent.*

// Create FSM with clean infix syntax
val orderMachine = Machine(assembly[OrderState, OrderEvent](
  Pending via Pay to Paid,
  Paid via Ship to Shipped,
  Shipped via Deliver to Delivered,
))
```

```scala mdoc:compile-only
// Run the FSM
val program = ZIO.scoped {
  for
    fsm   <- orderMachine.start(Pending)
    _     <- fsm.send(Pay)
    _     <- fsm.send(Ship)
    state <- fsm.currentState
  yield state // Shipped
}
```

---

[Back to Index](DOCUMENTATION.md) | [Next: Core Concepts >>](core-concepts.md)
