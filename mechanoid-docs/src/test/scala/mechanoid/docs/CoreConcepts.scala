package mechanoid.docs

import mechanoid.docs.DocZIO.*
import mechanoid.*
import specular.*
import zio.*
import zio.test.*

object CoreConcepts extends MechanoidDocSpecSuite:

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

  def doc = page("Core Concepts")(
    section("States and events")(
      md"""
States and events are plain Scala 3 enums (or sealed traits) that derive `Finite`:

```scala
enum TrafficLight derives Finite:
  case Red, Yellow, Green

enum LightEvent derives Finite:
  case Timer
```

`Finite` proves the type is sealed and non-empty so Mechanoid can validate assemblies and
visualize the full case set.
""",
      exampleZIO {
        ZIO.scoped {
          for
            fsm   <- trafficMachine.start(Red)
            _     <- fsm.send(Timer)
            state <- fsm.currentState
          yield state
        }.asDoc
      }.assert(state => assertTrue(state == Green)),
    ),
    section("Transitions")(
      md"""
```mermaid
stateDiagram-v2
  [*] --> Red
  Red --> Green: Timer
  Green --> Yellow: Timer
  Yellow --> Red: Timer
```

A transition is `State via Event to Target`. Targets can be a concrete state, `stay`, or `stop`.
Rich states (cases with fields) match by shape: a transition from `Failed` matches any `Failed(_)`.
"""
    ),
    section("Hierarchical states")(
      md"""
Organize related states with sealed traits and use `all[T]` for group transitions:

```mermaid
flowchart TB
  subgraph Order [OrderState]
    Created
    subgraph Processing
      ValidatingPayment
      ChargingCard
    end
    Completed
    Cancelled
  end
  Created -->|Start| ValidatingPayment
  ValidatingPayment -->|Charge| ChargingCard
  ChargingCard -->|Finish| Completed
  ValidatingPayment -->|Cancel| Cancelled
  ChargingCard -->|Cancel| Cancelled
  class Created,ValidatingPayment,ChargingCard,Completed,Cancelled happy
```
""",
      exampleZIO {
        ZIO.scoped {
          for
            fsm   <- hierarchicalMachine.start(Created)
            _     <- fsm.send(Start)
            _     <- fsm.send(Cancel)
            state <- fsm.currentState
          yield state
        }.asDoc
      }.assert(state => assertTrue(state == Cancelled)),
      md"""
Next: [Defining FSMs](defining-fsms.html) for assemblies, timeouts, and compile-time checks.
""",
    ),
  )
end CoreConcepts
