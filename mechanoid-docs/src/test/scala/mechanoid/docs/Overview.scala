package mechanoid.docs

import mechanoid.docs.DocZIO.*
import mechanoid.*
import specular.*
import zio.*
import zio.test.*

object Overview extends MechanoidDocSpecSuite:

  enum OrderState derives Finite:
    case Pending, Paid, Shipped, Delivered

  enum OrderEvent derives Finite:
    case Pay, Ship, Deliver

  import OrderState.*, OrderEvent.*

  val orderMachine = Machine(
    assembly[OrderState, OrderEvent](
      Pending via Pay to Paid,
      Paid via Ship to Shipped,
      Shipped via Deliver to Delivered,
    )
  )

  def doc = page("Overview")(
    md"""
**Mechanoid** is a type-safe, effect-oriented finite state machine library for Scala 3 built on ZIO.

ZIO already gives you excellent effect composition. Many domains are also finite state machines:
orders, payments, onboarding, provisioning. Mechanoid makes that graph explicit and typed:
states and events as Scala 3 enums, transitions as ZIO effects, assemblies validated at compile time.

```mermaid
flowchart LR
  Define[Define Assembly] --> Run[In-memory Runtime]
  Run --> Persist[EventStore + Snapshots]
  Persist --> Timeouts[Durable TimeoutStore]
  Timeouts --> Dist[Locks and Leader Election]
  class Define,Run,Persist,Timeouts,Dist happy
```

Each rung on that ladder is optional. Start with a Machine in memory; plug in persistence,
durable timeouts, and distributed coordination as ZIO layers when the app needs them.
""",
    section("A first machine")(
      md"""Define states and events, assemble transitions with infix syntax, then run:""",
      exampleZIO {
        ZIO.scoped {
          for
            fsm   <- orderMachine.start(Pending)
            _     <- fsm.send(Pay)
            _     <- fsm.send(Ship)
            state <- fsm.currentState
          yield state
        }.asDoc
      }.assert(state => assertTrue(state == Shipped)),
    ),
    section("What you get")(
      md"""
- **Declarative DSL** — `State via Event to Target`
- **Compile-time validation** — duplicate transitions, override orphans, produced-event types
- **Hierarchical states** — nested sealed traits and `all[T]` group transitions
- **Composable assemblies** — reusable fragments with `++` / `combine`
- **ZIO on every edge** — entry effects, producing effects, full env and error support
- **Optional production rungs** — event sourcing, durable timeouts, distributed locks

Next: [Why Mechanoid](why-mechanoid.html) for how it fits a ZIO stack, or
[Quick Start](quick-start.html) to install and run.
"""
    ),
  )
end Overview
