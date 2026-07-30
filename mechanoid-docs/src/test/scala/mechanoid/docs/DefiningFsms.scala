package mechanoid.docs

import mechanoid.docs.DocZIO.*
import mechanoid.*
import specular.*
import specular.mermoid.Mermoid
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
    case object Escalated    extends ProcState

    enum ProcEvent derives Finite:
      case Cancel, Escalate

    import ProcEvent.*

    val groupMachine = Machine(
      assembly[ProcState, ProcEvent](
        all[Processing] via Cancel to Cancelled,
      )
    )

    // Concrete duplicate + override (same pattern as `all[T]` leaves that need a special case).
    val overrideMachine = Machine(
      assembly[ProcState, ProcEvent](
        RegularState via Cancel to Cancelled,
        SpecialState via Cancel to Cancelled,
        (SpecialState via Cancel to Escalated) @@ Aspect.overriding,
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

  object Composed:
    enum ShipState derives Finite:
      case Draft, Paid, Packed, Shipped

    enum ShipEvent derives Finite:
      case Pay, Pack, Ship

    import ShipState.*, ShipEvent.*

    // Named fragments: use `inline def` so `++` / Machine can see the trees.
    // Nested objects may still need the assemblies written inline into Machine.
    val machine = Machine(
      assembly[ShipState, ShipEvent](
        Draft via Pay to Paid
      ) ++ assembly[ShipState, ShipEvent](
        Paid via Pack to Packed,
        Packed via Ship to Shipped,
      )
    )
  end Composed

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
`Machine(...)` so orphan-override detection can see the expression tree. Compose with
`assembly[…](…) ++ assembly[…](…)` (or top-level `inline def` fragments) so the macro can
still see both sides.
""",
      example {
        import Basic.*
        Mermoid.diagram(
          MermaidVisualizer.stateDiagram(machine, Some(Basic.MyState.State1)),
          DocsDiagrams.diagramConfig,
        )
      }.assert(ui => assertTrue(ui.toString.nonEmpty)),
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
| Case collisions | Distinct cases that hash alike (`CaseHasher`); rename the case |

`all[T]` expands to every leaf under `T`. Here both processing leaves cancel the same way:
""",
      example {
        import Hierarchical.*
        Mermoid.diagram(
          MermaidVisualizer.flowchart(groupMachine),
          DocsDiagrams.diagramConfig,
        )
      }.assert(ui => assertTrue(ui.toString.nonEmpty)),
      exampleZIO {
        import Hierarchical.*, Hierarchical.ProcEvent.*
        ZIO.scoped {
          for
            fromSpecial <- groupMachine.start(SpecialState).flatMap(fsm => fsm.send(Cancel) *> fsm.currentState)
            fromRegular <- groupMachine.start(RegularState).flatMap(fsm => fsm.send(Cancel) *> fsm.currentState)
          yield (fromSpecial, fromRegular)
        }.asDoc
      }.assert { case (fromSpecial, fromRegular) =>
        assertTrue(fromSpecial == Hierarchical.Cancelled) &&
        assertTrue(fromRegular == Hierarchical.Cancelled)
      },
    ),
    section("Intentional overrides")(
      md"""
When one leaf needs different behavior, declare the broader edge first, then the specific edge
with `@@ Aspect.overriding` (last wins). Here `SpecialState` escalates instead of cancelling:
""",
      example {
        import Hierarchical.*
        Mermoid.diagram(
          MermaidVisualizer.flowchart(overrideMachine),
          DocsDiagrams.diagramConfig,
        )
      }.assert(ui => assertTrue(ui.toString.nonEmpty)),
      exampleZIO {
        import Hierarchical.*, Hierarchical.ProcEvent.*
        ZIO.scoped {
          for
            regular <- overrideMachine.start(RegularState).flatMap(fsm => fsm.send(Cancel) *> fsm.currentState)
            special <- overrideMachine.start(SpecialState).flatMap(fsm => fsm.send(Cancel) *> fsm.currentState)
          yield (regular, special)
        }.asDoc
      }.assert { case (regular, special) =>
        assertTrue(regular == Hierarchical.Cancelled) &&
        assertTrue(special == Hierarchical.Escalated)
      },
    ),
    section("Timeouts on transitions")(
      md"""
Attach a deadline with `@@ Aspect.timeout(duration, timeoutEvent)`. Fiber-based timeouts fire
in-process; pair with [Durable Timeouts](durable-timeouts.html) when deadlines must survive
node failure. DocSpecs send the timeout event directly rather than waiting out the clock.

Entry/exit effects on assemblies (`.onEnter` / `.onExit`) and per-transition `.onEntry` /
`.producing` are covered on [Side Effects](side-effects.html).
""",
      exampleZIO {
        import Timed.*, Timed.PayState.*, Timed.PayEvent.*
        ZIO.scoped {
          for
            fsm   <- timedMachine.start(Pending)
            _     <- fsm.send(StartPayment)
            _     <- fsm.send(PaymentTimeout)
            state <- fsm.currentState
          yield state
        }.asDoc
      }.assert(state => assertTrue(state == Timed.PayState.Cancelled)),
    ),
    section("Composable assemblies")(
      md"""
Build reusable fragments and combine them with `++` / `combine`. Duplicates across combined
assemblies are still detected at compile time when composed inline into `Machine`.

Block form `assemblyAll[S, E]:` avoids commas between specs when the list gets long.
""",
      example {
        import Composed.*
        Mermoid.diagram(
          MermaidVisualizer.stateDiagram(machine, Some(Composed.ShipState.Draft)),
          DocsDiagrams.diagramConfig,
        )
      }.assert(ui => assertTrue(ui.toString.nonEmpty)),
      exampleZIO {
        import Composed.*, Composed.ShipState.*, Composed.ShipEvent.*
        ZIO.scoped {
          for
            fsm   <- machine.start(Draft)
            _     <- fsm.send(Pay)
            _     <- fsm.send(Pack)
            _     <- fsm.send(Ship)
            state <- fsm.currentState
          yield state
        }.asDoc
      }.assert(state => assertTrue(state == Composed.ShipState.Shipped)),
      md"""
Next: [Side Effects](side-effects.html).
""",
    ),
  )
end DefiningFsms
