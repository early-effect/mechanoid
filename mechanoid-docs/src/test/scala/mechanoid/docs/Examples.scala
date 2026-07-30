package mechanoid.docs

import specular.*
import zio.test.*

object Examples extends MechanoidDocSpecSuite:

  def doc = page("Examples")(
    md"""
The `examples` module in the repo ships runnable programs that climb the production ladder.
""",
    section("In the repository")(
      md"""
| Example | Path | Shows |
|---------|------|-------|
| Heartbeat | `examples/.../heartbeat` | Producing effects, durable timeouts, sweeper |
| Hierarchical | `examples/.../hierarchical` | Nested sealed states, document workflow |
| Pet store | `examples/.../petstore` | Larger domain FSM with services |

Clone [early-effect/mechanoid](https://github.com/early-effect/mechanoid) and run from sbt:

```bash
sbt examples/run
# or a specific main, e.g. mechanoid.examples.heartbeat.Main
```
""",
      exampleValue("examples").assert(s => assertTrue(s.nonEmpty)),
    ),
    section("Where to go next")(
      md"""
- [Overview](overview.html) — story and ladder
- [Quick Start](quick-start.html) — install and first `send`
- [Durable Timeouts](durable-timeouts.html) / [Distributed Coordination](distributed-coordination.html) — production rungs
- Hub: [earlyeffect.rocks](https://www.earlyeffect.rocks)
"""
    ),
  )
end Examples
