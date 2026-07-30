package mechanoid.docs

import specular.*
import zio.test.*

object Examples extends MechanoidDocSpecSuite:

  def doc = page("Examples")(
    md"""
Walk the domain pages first for asserted slices and rendered graphs, then clone the repo for
full runnable mains (Postgres, sweepers, services).
""",
    section("On this site")(
      md"""
| Page | Shows |
|------|-------|
| [Document Workflow](document-workflow.html) | Nested sealed states, `all[T]`, `++` |
| [Heartbeat](heartbeat.html) | `.producing` + `@@ Aspect.timeout` |
| [Orders](orders.html) | `event[T]` payloads, `to stay` on timeout |
"""
    ),
    section("In the repository")(
      md"""
| Example | Path | Adds beyond the DocSpec |
|---------|------|-------------------------|
| Hierarchical | `examples/.../hierarchical` | Full document lifecycle |
| Heartbeat | `examples/.../heartbeat` | Postgres stores, `TimeoutSweeper` |
| Pet store | `examples/.../petstore` | Richer events and service wiring |

```bash
sbt "examples/runMain mechanoid.examples.heartbeat.Main"
```
"""
    ),
    section("Where to go next")(
      md"""
- [Overview](overview.html) — story and ladder
- [Testing](testing.html) — how DocSpecs and unit tests share patterns
- Hub: [earlyeffect.rocks](https://www.earlyeffect.rocks)
"""
    ),
  )
end Examples
