package mechanoid.docs

import mechanoid.docs.DocZIO.*
import mechanoid.*
import specular.*
import zio.*
import zio.test.*

object DefiningFsms extends MechanoidDocSpecSuite:

  object Basic:
    enum MyState derives Finite:
      case State1, State2, State3

    enum MyEvent derives Finite:
      case Event1, Event2, Event3

    import MyState.*, MyEvent.*

    val machine = Machine(
      assembly[MyState, MyEvent](
        State1 via Event1 to State2,
        State1 via Event2 to stay,
        State2 via Event3 to State3,
      )
    )
  end Basic

  object Hierarchical:
    sealed trait ProcState derives Finite
    sealed trait Processing  extends ProcState derives Finite
    case object SpecialState extends Processing
    case object RegularState extends Processing
    case object Cancelled    extends ProcState
    case object Special      extends ProcState

    enum ProcEvent derives Finite:
      case Cancel

    import ProcEvent.*

    val groupMachine = Machine(
      assembly[ProcState, ProcEvent](
        all[Processing] via Cancel to Cancelled
      )
    )
  end Hierarchical

  object Timed:
    enum PayState derives Finite:
      case Pending, AwaitingPayment, Paid, Cancelled

    enum PayEvent derives Finite:
      case StartPayment, ConfirmPayment, PaymentTimeout

    import PayState.*, PayEvent.*

    val timedMachine = Machine(
      assembly[PayState, PayEvent](
        (Pending via StartPayment to AwaitingPayment) @@ Aspect.timeout(30.minutes, PaymentTimeout),
        AwaitingPayment via ConfirmPayment to Paid,
        AwaitingPayment via PaymentTimeout to Cancelled,
      )
    )
  end Timed

  def doc = page("Defining FSMs")(
    section("Assembly and Machine")(
      md"""
```mermaid
flowchart LR
  Specs[Transition specs] --> Asm[assembly macro]
  Asm --> Machine[Machine]
  Machine --> Runtime[FSMRuntime]
  class Specs,Asm,Machine,Runtime happy
```

`assembly[S, E](...)` validates transitions at compile time. Pass the assembly **inline** to
`Machine(...)` so orphan-override detection can see the expression tree.
""",
      exampleZIO {
        import Basic.*, Basic.MyState.*, Basic.MyEvent.*
        ZIO.scoped {
          for
            fsm   <- machine.start(State1)
            _     <- fsm.send(Event1)
            state <- fsm.currentState
          yield state
        }.asDoc
      }.assert(state => assertTrue(state == Basic.MyState.State2)),
    ),
    section("Compile-time safety")(
      md"""
Mechanoid catches many mistakes before runtime:

| Check | What fails |
|-------|------------|
| `Finite` derivation | Non-sealed or empty types |
| Duplicate transitions | Same state+event twice without `@@ Aspect.overriding` |
| Orphan overrides | `@@ Aspect.overriding` with nothing to override (warning) |
| Inline assembly | `Machine(valAssembly)` when orphan detection needs the tree |
| Produced events | `.producing` returning an unrelated event type |

Intentional overrides after `all[T]` use `@@ Aspect.overriding` (see the hierarchical example
in the repo). Group transitions:
""",
      exampleZIO {
        import Hierarchical.*, Hierarchical.ProcEvent.*
        ZIO.scoped {
          for
            fsm   <- groupMachine.start(SpecialState)
            _     <- fsm.send(Cancel)
            state <- fsm.currentState
          yield state
        }.asDoc
      }.assert(state => assertTrue(state == Hierarchical.Cancelled)),
    ),
    section("Timeouts on transitions")(
      md"""
Attach a deadline with `@@ Aspect.timeout(duration, timeoutEvent)`. Fiber-based timeouts fire
in-process; pair with [Durable Timeouts](durable-timeouts.html) when deadlines must survive
node failure.
""",
      exampleZIO {
        import Timed.*, Timed.PayState.*, Timed.PayEvent.*
        ZIO.scoped {
          for
            fsm   <- timedMachine.start(Pending)
            _     <- fsm.send(StartPayment)
            state <- fsm.currentState
          yield state
        }.asDoc
      }.assert(state => assertTrue(state == Timed.PayState.AwaitingPayment)),
    ),
    section("Composable assemblies")(
      md"""
Build reusable fragments and combine them with `++` / `combine`. Duplicates across combined
assemblies are still detected at compile time when composed inline into `Machine`.

```scala
val payments = assembly[S, E](...)
val shipping = assembly[S, E](...)
val machine  = Machine(payments ++ shipping)
```

Block form `assemblyAll[S, E]:` avoids commas between specs when the list gets long.

Next: [Side Effects](side-effects.html).
"""
    ),
  )
end DefiningFsms
