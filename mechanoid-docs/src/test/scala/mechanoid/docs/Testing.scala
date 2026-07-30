package mechanoid.docs

import mechanoid.docs.DocZIO.*
import mechanoid.*
import specular.*
import zio.*
import zio.test.*

object Testing extends MechanoidDocSpecSuite:

  enum Light derives Finite:
    case Red, Green

  enum Tick derives Finite:
    case Go, Timeout

  import Light.*, Tick.*

  val machine = Machine(
    assembly[Light, Tick](
      (Red via Go to Green) @@ Aspect.timeout(1.minute, Timeout),
      Green via Timeout to Red,
    )
  )

  def doc = page("Testing")(
    md"""
Mechanoid machines are ordinary ZIO values. Prefer the same scoped `start` / `send` style in
unit tests that you use on this site.
""",
    section("Scoped unit tests")(
      md"""
Assert outcomes and state after a short path:
""",
      exampleZIO {
        ZIO.scoped {
          for
            fsm     <- machine.start(Red)
            outcome <- fsm.send(Go)
            state   <- fsm.currentState
          yield (outcome.result, state)
        }.asDoc
      }.assert { case (result, state) =>
        assertTrue(result == TransitionResult.Goto(Green)) &&
        assertTrue(state == Green)
      },
    ),
    section("Negative paths")(
      md"""
Illegal events surface as `InvalidTransitionError` (use `.either`):
""",
      exampleZIO {
        ZIO.scoped {
          for
            fsm    <- machine.start(Red)
            failed <- fsm.send(Timeout).either
          yield failed
        }.asDoc
      }.assert { failed =>
        assertTrue(failed.isLeft) &&
        assertTrue(failed.swap.exists(_.isInstanceOf[InvalidTransitionError[?, ?]]))
      },
    ),
    section("Timeouts in tests")(
      md"""
| Approach | When |
|----------|------|
| Send the timeout event | Deterministic DocSpecs / live clock (this site) |
| `TestClock.adjust` | Fiber timeouts in zio-test without sleeping wall time |

This site uses `TestAspect.withLiveClock` because EventStore timestamps and producing sleeps need
a real clock; synthetic timeout events keep examples snappy.
""",
      exampleZIO {
        ZIO.scoped {
          for
            fsm   <- machine.start(Red)
            _     <- fsm.send(Go)
            _     <- fsm.send(Timeout)
            state <- fsm.currentState
          yield state
        }.asDoc
      }.assert(state => assertTrue(state == Red)),
    ),
    section("Layers and docs-as-tests")(
      md"""
Reuse the same `InMemoryEventStore` / `TimeoutStrategy` / `LockingStrategy` layers as the
production ladder pages. Specular DocSpecs **are** the suite: `docs/test` and `docs/specularSite`
fail when an assertion fails. Machines rendered with mermoid parse the Mermaid Mechanoid emits,
so the published picture cannot drift from the graph you run.

See [Visualization](visualization.html) and the [Domains](document-workflow.html) pages.
"""
    ),
  )
end Testing
