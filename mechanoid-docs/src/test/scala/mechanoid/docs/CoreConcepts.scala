package mechanoid.docs

import mechanoid.docs.DocZIO.*
import mechanoid.*
import specular.*
import zio.*
import zio.test.*

object CoreConcepts extends MechanoidDocSpecSuite:

  def doc = page("Core Concepts")(
    section("States and events")(
      md"""
States and events are plain Scala 3 enums (or sealed traits) that derive `Finite`. `Finite`
proves the type is sealed and non-empty so Mechanoid can validate assemblies and visualize the
full case set. Nested sealed traits are a tree: a leaf has one sealed parent. Only leaf cases
are runtime states. `all[T]` names the leaves `T` denotes.
""",
      example {
        enum TrafficLight derives Finite:
          case Red, Yellow, Green

        enum LightEvent derives Finite:
          case Timer

        import TrafficLight.*, LightEvent.*

        val trafficMachine = Machine(
          assembly[TrafficLight, LightEvent](
            Red via Timer to Green,
            Green via Timer to Yellow,
            Yellow via Timer to Red,
          )
        )

        DocsDiagram.paint(MermaidVisualizer.stateDiagram(trafficMachine, Some(Red)))
      }.assert(ui => assertTrue(ui.toString.contains("mermoid-root"))),
      exampleZIO {
        enum TrafficLight derives Finite:
          case Red, Yellow, Green

        enum LightEvent derives Finite:
          case Timer

        import TrafficLight.*, LightEvent.*

        val trafficMachine = Machine(
          assembly[TrafficLight, LightEvent](
            Red via Timer to Green,
            Green via Timer to Yellow,
            Yellow via Timer to Red,
          )
        )

        ZIO.scoped {
          for
            fsm   <- trafficMachine.start(Red)
            _     <- fsm.send(Timer)
            state <- fsm.currentState
          yield state
        }.asDoc
      }.assert(state => assertTrue(state.toString == "Green")),
    ),
    section("Transitions and matchers")(
      md"""
A transition is `State via Event to Target`. Targets can be a concrete state, `stay`, or
`stop` / `stop("reason")`. Rich states (cases with fields) match by shape.

| Matcher | Meaning |
|---------|---------|
| `all[T]` | The leaves `T` denotes: a sealed parent, a union, or an alias of either |
| `anyOf(s1, s2, …)` | An unnamed list of state values |
| `state[S]` | One state leaf. A parent or a union is `all[S]` |
| `event[E]` | One event leaf. A parent or a union is `all[E]` |
| `viaAnyOf` / `anyOfEvents` | Several events from one state (or group) |
| `viaAll` | Every event under an event parent type |
""",
      example {
        sealed trait GateState derives Finite
        case object Idle                  extends GateState
        case object Open                  extends GateState
        case object Locked                extends GateState
        case object Closed                extends GateState
        case class Failed(reason: String) extends GateState
        case class Retrying(attempt: Int) extends GateState

        enum GateEvent derives Finite:
          case Badge, Panic, Close, Tick, Fail, Retry

        import GateEvent.*

        val matcherMachine = Machine(
          assembly[GateState, GateEvent](
            Idle via Badge to Open,
            anyOf(Open, Locked) via Panic to Closed,
            Open via Tick to stay,
            Open via Fail to Failed("boom"),
            state[Failed] via Retry to Retrying(1),
            Closed via Panic to stop("already closed"),
          )
        )

        DocsDiagram.paint(MermaidVisualizer.flowchart(matcherMachine))
      }.assert(ui => assertTrue(ui.toString.contains("mermoid-root"))),
      exampleZIO {
        sealed trait GateState derives Finite
        case object Idle                  extends GateState
        case object Open                  extends GateState
        case object Locked                extends GateState
        case object Closed                extends GateState
        case class Failed(reason: String) extends GateState
        case class Retrying(attempt: Int) extends GateState

        enum GateEvent derives Finite:
          case Badge, Panic, Close, Tick, Fail, Retry

        import GateEvent.*

        val matcherMachine = Machine(
          assembly[GateState, GateEvent](
            Idle via Badge to Open,
            anyOf(Open, Locked) via Panic to Closed,
            Open via Tick to stay,
            Open via Fail to Failed("boom"),
            state[Failed] via Retry to Retrying(1),
            Closed via Panic to stop("already closed"),
          )
        )

        ZIO.scoped {
          for
            fsm     <- matcherMachine.start(Open)
            stayed  <- fsm.send(Tick)
            _       <- fsm.send(Fail)
            retried <- fsm.send(Retry)
            state   <- fsm.currentState
          yield (stayed.result, retried.result, state)
        }.asDoc
      }.assert { case (stayed, retried, state) =>
        assertTrue(stayed.toString.contains("Open")) &&
        assertTrue(retried.toString.contains("Retrying")) &&
        assertTrue(state.toString.contains("Retrying"))
      },
      exampleZIO {
        sealed trait GateState derives Finite
        case object Idle                  extends GateState
        case object Open                  extends GateState
        case object Locked                extends GateState
        case object Closed                extends GateState
        case class Failed(reason: String) extends GateState
        case class Retrying(attempt: Int) extends GateState

        enum GateEvent derives Finite:
          case Badge, Panic, Close, Tick, Fail, Retry

        import GateEvent.*

        val matcherMachine = Machine(
          assembly[GateState, GateEvent](
            Idle via Badge to Open,
            anyOf(Open, Locked) via Panic to Closed,
            Open via Tick to stay,
            Open via Fail to Failed("boom"),
            state[Failed] via Retry to Retrying(1),
            Closed via Panic to stop("already closed"),
          )
        )

        ZIO.scoped {
          for
            fsm     <- matcherMachine.start(Closed)
            outcome <- fsm.send(Panic)
            running <- fsm.isRunning
          yield (outcome.result, running)
        }.asDoc
      }.assert { case (result, running) =>
        assertTrue(result == TransitionResult.Stop(Some("already closed"))) &&
        assertTrue(!running)
      },
    ),
    section("Computed payloads")(
      md"""
`Finite` hashes by **case name**, not payload. Constant `to Retrying(1)` always lands on attempt `1`.
When `S` is the durable snapshot, compute the next instance from `(current, event)`.

The graph stays Finite: the function may only build the **named leaf**. `.to(stay) { ... }`
rewrites the instance without resetting timeouts. `.to[Leaf] { ... }` is a Goto (exit/entry/timeout
restart). A reducer that fails is an action failure; the event is not appended.
""",
      example {
        sealed trait GateState derives Finite
        case object Idle                  extends GateState
        case class Failed(reason: String) extends GateState
        case class Retrying(attempt: Int) extends GateState

        enum GateEvent derives Finite:
          case Fail, Retry, Tick

        import GateEvent.*

        val payloadMachine = Machine(
          assembly[GateState, GateEvent](
            Idle via Fail to Failed("boom"),
            (state[Failed] via Retry).to[Retrying] { (_, _) => Retrying(1) },
            (state[Retrying] via Retry).to(stay) { (r, _) => Retrying(r.attempt + 1) },
            state[Retrying] via Tick to stay,
          )
        )

        DocsDiagram.paint(MermaidVisualizer.flowchart(payloadMachine))
      }.assert(ui => assertTrue(ui.toString.contains("mermoid-root"))),
      exampleZIO {
        sealed trait GateState derives Finite
        case object Idle                  extends GateState
        case class Failed(reason: String) extends GateState
        case class Retrying(attempt: Int) extends GateState

        enum GateEvent derives Finite:
          case Fail, Retry, Tick

        import GateEvent.*

        val payloadMachine = Machine(
          assembly[GateState, GateEvent](
            Idle via Fail to Failed("boom"),
            (state[Failed] via Retry).to[Retrying] { (_, _) => Retrying(1) },
            (state[Retrying] via Retry).to(stay) { (r, _) => Retrying(r.attempt + 1) },
            state[Retrying] via Tick to stay,
          )
        )

        ZIO.scoped {
          for
            fsm   <- payloadMachine.start(Idle)
            _     <- fsm.send(Fail)
            _     <- fsm.send(Retry)
            _     <- fsm.send(Retry)
            _     <- fsm.send(Tick)
            state <- fsm.currentState
            hist  <- fsm.history
          yield (state, hist)
        }.asDoc
      }.assert { case (state, hist) =>
        assertTrue(state.toString.contains("Retrying(2)"), !hist.toString.contains("Retrying"))
      },
    ),
    section("Hierarchical states")(
      md"""
Organize related states with sealed traits and use `all[T]` for group transitions. Here every
`Processing` leaf can `Cancel` to `Cancelled`:
""",
      example {
        sealed trait OrderState derives Finite
        case object Created           extends OrderState
        sealed trait Processing       extends OrderState derives Finite
        case object ValidatingPayment extends Processing
        case object ChargingCard      extends Processing
        case object Completed         extends OrderState
        case object Cancelled         extends OrderState

        enum OrderEvent derives Finite:
          case Start, Charge, Finish, Cancel

        import OrderEvent.*

        val hierarchicalMachine = Machine(
          assembly[OrderState, OrderEvent](
            Created via Start to ValidatingPayment,
            ValidatingPayment via Charge to ChargingCard,
            ChargingCard via Finish to Completed,
            all[Processing] via Cancel to Cancelled,
          )
        )

        DocsDiagram.paint(MermaidVisualizer.flowchart(hierarchicalMachine))
      }.assert(ui => assertTrue(ui.toString.contains("mermoid-root"))),
      exampleZIO {
        sealed trait OrderState derives Finite
        case object Created           extends OrderState
        sealed trait Processing       extends OrderState derives Finite
        case object ValidatingPayment extends Processing
        case object ChargingCard      extends Processing
        case object Completed         extends OrderState
        case object Cancelled         extends OrderState

        enum OrderEvent derives Finite:
          case Start, Charge, Finish, Cancel

        import OrderEvent.*

        val hierarchicalMachine = Machine(
          assembly[OrderState, OrderEvent](
            Created via Start to ValidatingPayment,
            ValidatingPayment via Charge to ChargingCard,
            ChargingCard via Finish to Completed,
            all[Processing] via Cancel to Cancelled,
          )
        )

        ZIO.scoped {
          for
            fsm   <- hierarchicalMachine.start(Created)
            _     <- fsm.send(Start)
            _     <- fsm.send(Cancel)
            state <- fsm.currentState
          yield state
        }.asDoc
      }.assert(state => assertTrue(state.toString == "Cancelled")),
      md"""
Next: a set that is not itself a node in that tree.
""",
    ),
    section("Groups that are not a parent")(
      md"""
`all[Parent]` is the family that already exists in the sealed tree. A set that cuts across
the tree is a type alias of a union, then `all[Alias]`. The leaf does not grow a second
`extends`. A list you use once, and do not want to name, stays `anyOf`.

`state[T]` and `event[T]` name one leaf. A parent or a union does not compile: write `all[T]`.
The same edge from `all[Alias]` and from one of its leaves is a duplicate. See
[Defining FSMs](defining-fsms.html) for `@@ Aspect.overriding`.

`Named` is `Draft | Live`. `Archived` is not in that set, so `SetName` has no edge there.
The reducer sees `Named`, with no ascription:
""",
      example {
        enum Doc derives Finite:
          case Draft(name: String)
          case Live(name: String)
          case Archived(name: String)

        enum DocEvent derives Finite:
          case SetName(name: String)

        import Doc.*
        import DocEvent.*

        type Named = Draft | Live

        val namedMachine = Machine(
          assembly[Doc, DocEvent](
            (all[Named] via event[SetName]).to(stay) { (s, e) =>
              s match
                case _: Draft => Draft(e.name)
                case _: Live  => Live(e.name)
            }
          )
        )

        DocsDiagram.paint(MermaidVisualizer.flowchart(namedMachine))
      }.assert { ui =>
        val text = ui.toString
        assertTrue(text.contains("Draft"), text.contains("Live"))
      },
      exampleZIO {
        enum Doc derives Finite:
          case Draft(name: String)
          case Live(name: String)
          case Archived(name: String)

        enum DocEvent derives Finite:
          case SetName(name: String)

        import Doc.*
        import DocEvent.*

        type Named = Draft | Live

        val namedMachine = Machine(
          assembly[Doc, DocEvent](
            (all[Named] via event[SetName]).to(stay) { (s, e) =>
              s match
                case _: Draft => Draft(e.name)
                case _: Live  => Live(e.name)
            }
          )
        )

        ZIO.scoped {
          for
            draftFsm <- namedMachine.start(Draft("old"))
            _        <- draftFsm.send(SetName("new"))
            draft    <- draftFsm.currentState
            liveFsm  <- namedMachine.start(Live("old"))
            _        <- liveFsm.send(SetName("new"))
            live     <- liveFsm.currentState
            keptFsm  <- namedMachine.start(Archived("keep"))
            missed   <- keptFsm.send(SetName("new")).either
            kept     <- keptFsm.currentState
          yield (draft.toString, live.toString, missed.isLeft, kept.toString)
        }.asDoc
      }.assert { case (draft, live, missed, kept) =>
        assertTrue(
          draft == "Draft(new)",
          live == "Live(new)",
          missed,
          kept == "Archived(keep)",
        )
      },
      md"""
Next: [Defining FSMs](defining-fsms.html) for assemblies, overrides, composition, and
compile-time checks.
""",
    ),
  )
end CoreConcepts
