package mechanoid.docs

import mechanoid.docs.DocZIO.*
import mechanoid.*
import specular.*
import specular.mermoid.Mermoid
import zio.*
import zio.test.*

/** Service heartbeat domain (from `examples/heartbeat`). */
object ServiceHeartbeat extends MechanoidDocSpecSuite:

  enum ServiceState derives Finite:
    case Stopped, Started, Degraded, Critical

  enum ServiceEvent derives Finite:
    case Start, Stop, ManualReset
    case HeartbeatTick, DegradedCheck
    case Healthy, Unstable, Failed

  import ServiceState.*, ServiceEvent.*

  val machine = Machine(
    assemblyAll[ServiceState, ServiceEvent]:
      (Stopped via Start to Started) @@ Aspect.timeout(10.seconds, HeartbeatTick)

      (Started via HeartbeatTick to Started)
        .producing { (_, _) => ZIO.succeed(Healthy) } @@ Aspect.timeout(10.seconds, HeartbeatTick)

      (Started via Healthy to Started) @@ Aspect.timeout(10.seconds, HeartbeatTick)

      (Started via Unstable to Degraded) @@ Aspect.timeout(3.seconds, DegradedCheck)

      (Degraded via DegradedCheck to Degraded)
        .producing { (_, _) => ZIO.succeed(Healthy) } @@ Aspect.timeout(3.seconds, DegradedCheck)

      (Degraded via Healthy to Started) @@ Aspect.timeout(10.seconds, HeartbeatTick)

      (Degraded via Failed to Critical) @@ Aspect.timeout(30.seconds, ManualReset)

      anyOf(Started, Degraded, Critical) via Stop to Stopped
      Critical via ManualReset to Started
  )

  private val machineSource =
    md"""
```scala
enum ServiceState derives Finite:
  case Stopped, Started, Degraded, Critical

enum ServiceEvent derives Finite:
  case Start, Stop, ManualReset
  case HeartbeatTick, DegradedCheck
  case Healthy, Unstable, Failed

val machine = Machine(
  assemblyAll[ServiceState, ServiceEvent]:
    (Stopped via Start to Started) @@ Aspect.timeout(10.seconds, HeartbeatTick)
    (Started via HeartbeatTick to Started)
      .producing { (_, _) => ZIO.succeed(Healthy) } @@ Aspect.timeout(10.seconds, HeartbeatTick)
    (Started via Healthy to Started) @@ Aspect.timeout(10.seconds, HeartbeatTick)
    (Started via Unstable to Degraded) @@ Aspect.timeout(3.seconds, DegradedCheck)
    (Degraded via DegradedCheck to Degraded)
      .producing { (_, _) => ZIO.succeed(Healthy) } @@ Aspect.timeout(3.seconds, DegradedCheck)
    (Degraded via Healthy to Started) @@ Aspect.timeout(10.seconds, HeartbeatTick)
    (Degraded via Failed to Critical) @@ Aspect.timeout(30.seconds, ManualReset)
    anyOf(Started, Degraded, Critical) via Stop to Stopped
    Critical via ManualReset to Started
)
```
"""

  def doc = page("Heartbeat")(
    md"""
A self-driving health machine: a timeout schedules the next check; `.producing` runs the check
and feeds the result back as an event. Degraded and critical tiers use shorter or recovery
deadlines.

(The timeout event is named `HeartbeatTick` here so it does not clash with this page’s object
name.)
""",
    machineSource,
    section("The graph")(
      example {
        Mermoid.diagram(
          MermaidVisualizer.flowchart(machine),
          Mermoid.chalkboard,
        )
      }.assert(ui => assertTrue(ui.toString.nonEmpty)),
      md"""
Features in play: `assemblyAll`, `.producing`, `@@ Aspect.timeout`, `anyOf`. Full stack with
Postgres stores and `TimeoutSweeper`: `examples/.../heartbeat`.
""",
    ),
    section("A timeout tick")(
      md"""
DocSpecs send the timeout event the sweeper would fire. After `HeartbeatTick`, the producing
effect returns `Healthy` and the machine stays in `Started` with a fresh deadline.
""",
      exampleZIO {
        ZIO.scoped {
          for
            fsm   <- machine.start(Stopped)
            _     <- fsm.send(Start)
            _     <- fsm.send(HeartbeatTick)
            _     <- ZIO.sleep(50.millis)
            state <- fsm.currentState
          yield state
        }.asDoc
      }.assert(state => assertTrue(state == Started)),
      md"""
Next: [Orders](orders.html) for rich `event[T]` payloads, or [Durable Timeouts](durable-timeouts.html)
for the production sweeper story.
""",
    ),
  )
end ServiceHeartbeat
