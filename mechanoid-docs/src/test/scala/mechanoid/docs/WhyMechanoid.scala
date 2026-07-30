package mechanoid.docs

import specular.*
import zio.test.*

object WhyMechanoid extends MechanoidDocSpecSuite:

  def doc = page("Why Mechanoid")(
    md"""
ZIO apps already excel at typed effects, composition, and resource safety. Mechanoid applies
those same values to the **control plane** of your domain: the finite set of states a workflow
can be in, and the events that move it.
""",
    section("A natural fit alongside ZIO")(
      md"""
```mermaid
flowchart TB
  subgraph zioStack [ZIO stack]
    Effects[Typed effects]
    Layers[Layers and resources]
    Test[zio-test]
  end
  subgraph mech [Mechanoid]
    Graph[Typed state graph]
    Asm[Compile-time assemblies]
    Runtime[FSMRuntime]
  end
  Effects --> Graph
  Layers --> Runtime
  Graph --> Runtime
  Runtime --> Domain[Durable domain workflows]
  class Effects,Layers,Test,Graph,Asm,Runtime,Domain happy
```

You keep writing ZIO. Mechanoid gives the state machine a first-class home:

- **States and events** are Scala 3 enums or sealed traits (`derives Finite`)
- **Transitions** are ZIO effects with your environment and error channel
- **Runtime** plugs into the same layer style you already use for services and stores
"""
    ),
    section("When the graph wants to be explicit")(
      md"""
Workflow-shaped domains (checkout, payment capture, document review, service heartbeats) have
an implicit state machine whether or not you name it. Making the graph explicit pays off when:

- Product and engineering need a shared picture of allowed moves
- Compile-time checks should catch duplicate or missing transitions
- You want persistence, timeouts, and locking as **optional layers**, not a rewrite

Mechanoid is not a replacement for ZIO, and it is not a heavyweight workflow engine. It is the
typed FSM layer that sits inside the ZIO application you already like.
"""
    ),
    section("The production ladder")(
      md"""
Grow capabilities without changing the machine definition:

| Rung | What you add | When |
|------|----------------|------|
| In-memory | `machine.start` | Local logic, tests, simple services |
| Persistence | `EventStore` + `FSMRuntime` | Survive restarts, audit the history |
| Durable timeouts | `TimeoutStore` + sweeper | Deadlines that outlive a node |
| Distributed | `FSMInstanceLock` / leader election | Multi-node, high contention |

Continue with [Quick Start](quick-start.html).
""",
      exampleValue(true).assert(v => assertTrue(v)),
    ),
  )
end WhyMechanoid
