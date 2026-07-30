package mechanoid.docs

import mechanoid.docs.DocZIO.*
import mechanoid.*
import specular.*
import specular.mermoid.Mermoid
import zio.*
import zio.test.*

object CoreConcepts extends MechanoidDocSpecSuite:

  def doc = page("Core Concepts")(
    section("States and events")(
      md"""
States and events are plain Scala 3 enums (or sealed traits) that derive `Finite`. `Finite`
proves the type is sealed and non-empty so Mechanoid can validate assemblies and visualize the
full case set. Nested sealed traits declare parent types for `all[T]`; only leaf cases are
runtime states.
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

        Mermoid.diagram(
          MermaidVisualizer.stateDiagram(trafficMachine, Some(Red)),
          DocsDiagrams.diagramConfig,
        )
      }.assert(ui => assertTrue(ui.toString.nonEmpty)),
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
| `all[T]` | Every leaf under sealed trait / enum parent `T` |
| `anyOf(s1, s2, …)` | Explicit list of states |
| `state[S]` | Match a state **type** (parameterized cases: `Failed(_)`) |
| `event[E]` | Match an event **type** (payload carriers) |
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

        Mermoid.diagram(
          MermaidVisualizer.flowchart(matcherMachine),
          DocsDiagrams.diagramConfig,
        )
      }.assert(ui => assertTrue(ui.toString.nonEmpty)),
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
        assertTrue(stayed == TransitionResult.Stay) &&
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

        Mermoid.diagram(
          MermaidVisualizer.flowchart(hierarchicalMachine),
          DocsDiagrams.diagramConfig,
        )
      }.assert(ui => assertTrue(ui.toString.nonEmpty)),
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
Next: [Defining FSMs](defining-fsms.html) for assemblies, overrides, composition, and
compile-time checks.
""",
    ),
  )
end CoreConcepts
