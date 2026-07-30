package mechanoid.docs

import mechanoid.docs.DocZIO.*
import mechanoid.*
import specular.*
import zio.*
import zio.test.*

object QuickStart extends MechanoidDocSpecSuite:

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

  def doc = page("Quick Start")(
    section("Install")(
      md"""
Add the core library (and ZIO, which Mechanoid marks as provided):

```scala
libraryDependencies += "rocks.earlyeffect" %% "mechanoid" % "0.3.2"
libraryDependencies += "dev.zio" %% "zio" % "2.1.26"

// Optional PostgreSQL persistence
libraryDependencies += "rocks.earlyeffect" %% "mechanoid-postgres" % "0.3.2"
```

Use the version shown on the site chrome / Maven Central badges; release tags are `v*`.
"""
    ),
    section("Define and run")(
      md"""
```scala
import mechanoid.*
import zio.*

enum OrderState derives Finite:
  case Pending, Paid, Shipped

enum OrderEvent derives Finite:
  case Pay, Ship

import OrderState.*, OrderEvent.*

val orderMachine = Machine(assembly[OrderState, OrderEvent](
  Pending via Pay to Paid,
  Paid via Ship to Shipped,
))
```
""",
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
      md"""
`assembly` validates transitions at compile time. `Machine(...)` makes the assembly runnable.
`start` gives you an in-memory `FSMRuntime` scoped to the ZIO scope.

Next: [Core Concepts](core-concepts.html) for states, events, and hierarchy.
""",
    ),
  )
end QuickStart
