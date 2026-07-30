# Mechanoid

[![CI](https://github.com/early-effect/mechanoid/actions/workflows/ci.yml/badge.svg)](https://github.com/early-effect/mechanoid/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/rocks.earlyeffect/mechanoid_3?logo=apachemaven&label=mechanoid)](https://central.sonatype.com/artifact/rocks.earlyeffect/mechanoid_3)
[![Maven Central - Postgres](https://img.shields.io/maven-central/v/rocks.earlyeffect/mechanoid-postgres_3?logo=apachemaven&label=mechanoid-postgres)](https://central.sonatype.com/artifact/rocks.earlyeffect/mechanoid-postgres_3)

A type-safe finite state machine library for Scala 3 and ZIO.

ZIO already gives you excellent effect composition. Many domains are also finite state machines.
Mechanoid makes that graph explicit and typed: states and events as enums, transitions as ZIO
effects, assemblies validated at compile time. Start in memory, then add persistence, durable
timeouts, and distributed coordination as ZIO layers when you need them.

**Docs:** [earlyeffect.rocks/mechanoid](https://www.earlyeffect.rocks/mechanoid/)

## Features

- **Declarative DSL** — `State via Event to Target`
- **Compile-time validation** — duplicate transitions, overrides, produced-event types
- **Hierarchical states** — nested sealed traits and `all[T]` group transitions
- **Composable assemblies** — reusable fragments with full compile-time checks
- **ZIO on every edge** — entry effects, producing effects, env and errors
- **Optional production rungs** — event sourcing, durable timeouts, distributed locks

## Installation

```scala
libraryDependencies += "rocks.earlyeffect" %% "mechanoid" % "0.3.2"
libraryDependencies += "dev.zio" %% "zio" % "2.1.26"

// Optional PostgreSQL persistence
libraryDependencies += "rocks.earlyeffect" %% "mechanoid-postgres" % "0.3.2"
```

## Quick Start

```scala
import mechanoid.*
import zio.*

enum OrderState derives Finite:
  case Pending, Paid, Shipped

enum OrderEvent derives Finite:
  case Pay, Ship

import OrderState.*, OrderEvent.*

val orderMachine = Machine(assembly[OrderState, OrderEvent](
  Pending via Pay to Paid,
  Paid via Ship to Shipped,
))

val program = ZIO.scoped {
  for
    fsm   <- orderMachine.start(Pending)
    _     <- fsm.send(Pay)
    _     <- fsm.send(Ship)
    state <- fsm.currentState
  yield state // Shipped
}
```

## Documentation

Full guide with Mermaid diagrams and docs-as-tests examples:

- [Overview](https://www.earlyeffect.rocks/mechanoid/) — story and production ladder
- [Quick Start](https://www.earlyeffect.rocks/mechanoid/quick-start.html)
- [Core Concepts](https://www.earlyeffect.rocks/mechanoid/core-concepts.html)
- [Persistence](https://www.earlyeffect.rocks/mechanoid/persistence.html) · [Durable Timeouts](https://www.earlyeffect.rocks/mechanoid/durable-timeouts.html) · [Distributed Coordination](https://www.earlyeffect.rocks/mechanoid/distributed-coordination.html)

## Development

```bash
./scripts/install-git-hooks  # once per clone: pre-commit runs scalafmtCheckAll
git config core.hooksPath hooks
sbt docs/test          # DocSpecs
sbt docs/specularSite  # build site to target/site
sbt docsPreview        # watch + serve
```

## License

Apache 2.0 — see [LICENSE](https://github.com/early-effect/mechanoid/blob/main/LICENSE)
