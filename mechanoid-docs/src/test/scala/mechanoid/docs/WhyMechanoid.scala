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
    section("Why finite state machines")(
      md"""
Many product workflows are graphs whether you name them or not: checkout, document review,
payment capture, service health. Allowed moves, cancellations, and deadlines are part of the
domain. When that graph stays implicit (a fold of flags and `if` branches), the picture lives
only in someone's head:

```mermaid
flowchart LR
  subgraph implicitFold [Implicit fold]
    Flags[Booleans and status codes]
    Branches[Ad hoc branches]
    Flags --> Branches
    Branches --> Bug["Invalid transition at 2am"]
  end
  subgraph explicitGraph [Explicit FSM]
    States[Typed states]
    Events[Typed events]
    Edges[Declared transitions]
    States --> Edges
    Events --> Edges
    Edges --> Picture[Shared picture of allowed moves]
  end
  class Bug sad
  class Picture happy
```

Making the graph explicit pays off when product and engineering need a shared picture of
allowed moves, when compile-time checks should catch duplicate or missing transitions, and
when persistence, timeouts, and locking should be **optional layers**, not a rewrite.
"""
    ),
    section("Why Mechanoid")(
      md"""
Mechanoid is the typed FSM layer that sits inside the ZIO application you already like:

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

- **States and events** are Scala 3 enums or sealed traits (`derives Finite`)
- **Transitions** are ZIO effects with your environment and error channel
- **Assemblies** are validated at compile time (duplicates, overrides, produced-event types)
- **Runtime** plugs into the same layer style you already use for services and stores

What is special is the **DSL and composability**: infix transitions, hierarchical `all[T]`,
reusable fragments with `++` / `assemblyAll`, and aspects like timeouts and intentional
overrides. It is not a generic actor FSM, and it is not a heavyweight workflow engine.
"""
    ),
    section("The production ladder")(
      md"""
Grow capabilities without changing the machine definition. [Overview](overview.html) walks the
same ladder with a first runnable machine.

| Rung | What you add | When |
|------|----------------|------|
| In-memory | `machine.start` | Local logic, tests, simple services |
| Persistence | `EventStore` + `FSMRuntime` | Survive restarts, audit the history |
| Durable timeouts | `TimeoutStore` + sweeper | Deadlines that outlive a node |
| Distributed | `FSMInstanceLock` / leader election | Multi-node, high contention |
"""
    ),
    section("Docs that cannot drift")(
      md"""
This site is built from Specular DocSpecs: every example asserts under zio-test when the site
is built. Machines are rendered with [mermoid](https://www.earlyeffect.rocks/mermoid/) from the
same definitions you run, so the picture, the suite, and the API stay aligned.

Continue with [Quick Start](quick-start.html), or dig into [Defining FSMs](defining-fsms.html)
for the DSL.
"""
    ),
  )
end WhyMechanoid
