# Mechanoid

[![Scala CI](https://github.com/russwyte/mechanoid/actions/workflows/scala.yml/badge.svg)](https://github.com/russwyte/mechanoid/actions/workflows/scala.yml)

On Maven Central
[![Maven Central - Core](https://img.shields.io/maven-central/v/io.github.russwyte/mechanoid_3?logo=apachemaven&label=mechanoid-central)](https://central.sonatype.com/artifact/io.github.russwyte/mechanoid_3)
[![Maven Central - Postgres](https://img.shields.io/maven-central/v/io.github.russwyte/mechanoid-postgres_3?logo=apachemaven&label=mechanoid-postgres-central)](https://central.sonatype.com/artifact/io.github.russwyte/mechanoid-postgres_3)

On Maven Repo
[![Maven Repository - Core](https://img.shields.io/maven-central/v/io.github.russwyte/mechanoid_3?logo=apachemaven&label=mechanoid-repo)](https://mvnrepository.com/artifact/io.github.russwyte/mechanoid)
[![Maven Repository - Postgres](https://img.shields.io/maven-central/v/io.github.russwyte/mechanoid-postgres_3?logo=apachemaven&label=mechanoid-postgres-repo)](https://mvnrepository.com/artifact/io.github.russwyte/mechanoid-postgres)


A type-safe, effect-oriented finite state machine library for Scala 3 built on ZIO.

## Features

- **Declarative DSL** - Clean infix syntax: `State via Event to Target`
- **Type-safe** - States and events are Scala 3 enums or sealed traits with compile-time validation
- **Hierarchical states** - Organize complex state spaces with nested sealed traits
- **Composable assemblies** - Build reusable FSM fragments and compose them with full compile-time validation
- **Effectful** - All transitions are ZIO effects with full environment and error support
- **Event sourcing** - Optional persistence with snapshots and optimistic locking
- **Durable timeouts** - Timeouts that survive node failures via database persistence
- **Distributed coordination** - Claim-based locking and optional leader election

## Installation

Add to your `build.sbt`:

```scala
// Core library
libraryDependencies += "io.github.russwyte" %% "mechanoid" % "0.3.2+1-5a763249+20260310-1121"

// PostgreSQL persistence (optional)
libraryDependencies += "io.github.russwyte" %% "mechanoid-postgres" % "0.3.2+1-5a763249+20260310-1121"
```

## Quick Start

```scala
import mechanoid.*
import zio.*

// Define states and events as plain enums
enum OrderState derives Finite:
  case Pending, Paid, Shipped

enum OrderEvent derives Finite:
  case Pay, Ship

import OrderState.*, OrderEvent.*

// Create FSM with clean infix syntax and compile-time validation
val orderMachine = Machine(assembly[OrderState, OrderEvent](
  Pending via Pay to Paid,
  Paid via Ship to Shipped,
))
```

```scala
// Run
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

See the [full documentation](docs/DOCUMENTATION.md) for:

- [Core Concepts](docs/core-concepts.md) - States, events, transitions
- [Defining FSMs](docs/defining-fsms.md) - Entry/exit actions, timeouts
- [Running FSMs](docs/running-fsms.md) - Runtime, sending events
- [Side Effects](docs/side-effects.md) - Entry effects, producing effects
- [Persistence](docs/persistence.md) - Event sourcing, snapshots, recovery
- [Durable Timeouts](docs/durable-timeouts.md) - TimeoutStore, sweepers, leader election
- [Distributed Locking](docs/distributed.md#distributed-locking) - Exactly-once transitions, FSMInstanceLock

## Key Components

| Component | Description |
|-----------|-------------|
| `assembly[S, E](...)` | Create reusable transition fragments with compile-time validation |
| `assemblyAll[S, E]: ...` | Block syntax for assemblies (no commas between specs) |
| `Machine(assembly)` | Create a runnable Machine from a validated Assembly |
| `combine(a1, a2)` / `a1 ++ a2` | Combine two assemblies with compile-time duplicate detection |
| `Machine[S, E]` | The FSM definition that can be started and run |
| `Assembly[S, E]` | Composable transition fragments (cannot run directly) |
| `FSMRuntime[Id, S, E]` | Unified FSM execution (in-memory or persistent) |
| `TimeoutStrategy[Id]` | Strategy for state timeouts (`fiber` or `durable`) |
| `LockingStrategy[Id]` | Strategy for concurrent access (`optimistic` or `distributed`) |
| `TimeoutSweeper` | Background service for durable timeouts |
| `FSMInstanceLock` | Distributed locking for exactly-once transitions |
| `LeaderElection` | Lease-based coordination for single-active mode |

## Example with Persistence

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

// Placeholder types for the example
type OrderId = String
val orderId: OrderId = "order-1"
val eventStoreLayer: zio.ULayer[EventStore[OrderId, OrderState, OrderEvent]] =
  InMemoryEventStore.layer[OrderId, OrderState, OrderEvent]
```

```scala
val program = ZIO.scoped {
  for
    fsm <- FSMRuntime(orderId, orderMachine, Pending)
    _   <- fsm.send(Pay)      // Persisted to EventStore
    _   <- fsm.saveSnapshot   // Optional: snapshot for faster recovery
  yield ()
}.provide(
  eventStoreLayer,
  TimeoutStrategy.fiber[OrderId],      // In-memory timeouts
  LockingStrategy.optimistic[OrderId]  // Optimistic concurrency control
)
```

## Example with Durable Timeouts

```scala
import mechanoid.*
import zio.*

enum PaymentState derives Finite:
  case Pending, AwaitingPayment, Paid, Cancelled

enum PaymentEvent derives Finite:
  case StartPayment, ConfirmPayment, PaymentTimeout

import PaymentState.*, PaymentEvent.*

// FSM with timeout that survives node failures
val paymentMachine = Machine(assembly[PaymentState, PaymentEvent](
  (Pending via StartPayment to AwaitingPayment) @@ Aspect.timeout(30.minutes, PaymentTimeout),
  AwaitingPayment via ConfirmPayment to Paid,
  AwaitingPayment via PaymentTimeout to Cancelled,
))

// Placeholder types for the example
type PaymentId = String
val id: PaymentId = "payment-1"
val eventStoreLayer: zio.ULayer[EventStore[PaymentId, PaymentState, PaymentEvent]] =
  InMemoryEventStore.layer[PaymentId, PaymentState, PaymentEvent]
val timeoutStoreLayer: zio.ULayer[TimeoutStore[PaymentId]] =
  ZLayer.fromZIO(InMemoryTimeoutStore.make[PaymentId])
```

```scala
val program = ZIO.scoped {
  for
    fsm <- FSMRuntime(id, paymentMachine, Pending)
    _   <- fsm.send(StartPayment)
    // Timeout persisted - another node's sweeper will fire it if this node dies
  yield ()
}.provide(
  eventStoreLayer,
  timeoutStoreLayer,
  TimeoutStrategy.durable[PaymentId],     // Persisted timeouts that survive node failures
  LockingStrategy.optimistic[PaymentId]
)
```

For sweeper setup, see examples/heartbeat - sweeper runs alongside FSMRuntime.

## Example with Distributed Locking

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

enum PaymentState derives Finite:
  case Pending, AwaitingPayment, Paid, Cancelled

enum PaymentEvent derives Finite:
  case StartPayment, ConfirmPayment, PaymentTimeout

val paymentMachine = Machine(assembly[PaymentState, PaymentEvent](
  (PaymentState.Pending via PaymentEvent.StartPayment to PaymentState.AwaitingPayment)
    @@ Aspect.timeout(30.minutes, PaymentEvent.PaymentTimeout),
  PaymentState.AwaitingPayment via PaymentEvent.ConfirmPayment to PaymentState.Paid,
  PaymentState.AwaitingPayment via PaymentEvent.PaymentTimeout to PaymentState.Cancelled,
))

// Placeholder types for the example
type OrderId = String
val orderId: OrderId = "order-1"
val eventStoreLayer: zio.ULayer[EventStore[OrderId, OrderState, OrderEvent]] =
  InMemoryEventStore.layer[OrderId, OrderState, OrderEvent]
val lockServiceLayer: zio.ULayer[FSMInstanceLock[OrderId]] =
  ZLayer.fromZIO(InMemoryFSMInstanceLock.make[OrderId])
val timeoutStoreLayer: zio.ULayer[TimeoutStore[OrderId]] =
  ZLayer.fromZIO(InMemoryTimeoutStore.make[OrderId])

val paymentEventStoreLayer: zio.ULayer[EventStore[OrderId, PaymentState, PaymentEvent]] =
  InMemoryEventStore.layer[OrderId, PaymentState, PaymentEvent]
```

```scala
// Exactly-once transitions across multiple nodes
val program = ZIO.scoped {
  for
    fsm <- FSMRuntime(orderId, orderMachine, Pending)
    _   <- fsm.send(Pay)  // Lock acquired automatically before processing
  yield ()
}.provide(
  eventStoreLayer,
  lockServiceLayer,                       // FSMInstanceLock implementation
  TimeoutStrategy.fiber[OrderId],
  LockingStrategy.distributed[OrderId]    // Acquires lock before each transition
)

// Production setup: durable timeouts + distributed locking
val robustProgram = ZIO.scoped {
  for
    fsm <- FSMRuntime(orderId, paymentMachine, PaymentState.Pending)
    _   <- fsm.send(PaymentEvent.StartPayment)
  yield ()
}.provide(
  paymentEventStoreLayer,
  timeoutStoreLayer,
  lockServiceLayer,
  TimeoutStrategy.durable[OrderId],       // Timeouts survive node failures
  LockingStrategy.distributed[OrderId]    // Prevents concurrent modifications
)
```

## Requirements

- Scala 3.x
- ZIO 2.x

## Development

After cloning, set up the pre-commit hook to check formatting:

```bash
git config core.hooksPath hooks
```

This enables a pre-commit hook that runs `sbt scalafmtCheckAll`. If formatting fails, run `sbt scalafmtAll` to fix it.

## License

[Apache 2.0](LICENSE)
