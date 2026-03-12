# Durable Timeouts

[Back to Documentation Index](DOCUMENTATION.md)

---

## The Problem

In-memory timeouts (fiber-based) don't survive node failures. If a node crashes while an FSM is in a timed state, the timeout never fires.

## TimeoutStore

Persist timeout deadlines to a database:

```scala
import mechanoid.*
import zio.*
```

```scala
import java.time.Instant

trait TimeoutStore[Id]:
  def schedule(instanceId: Id, stateHash: Int, sequenceNr: Long, deadline: Instant): ZIO[Any, MechanoidError, ScheduledTimeout[Id]]
  def cancel(instanceId: Id): ZIO[Any, MechanoidError, Boolean]
  def queryExpired(limit: Int, now: Instant): ZIO[Any, MechanoidError, List[ScheduledTimeout[Id]]]
  def claim(instanceId: Id, nodeId: String, duration: Duration, now: Instant): ZIO[Any, MechanoidError, ClaimResult]
  def complete(instanceId: Id): ZIO[Any, MechanoidError, Boolean]
  def release(instanceId: Id): ZIO[Any, MechanoidError, Boolean]
```

The `stateHash` and `sequenceNr` parameters enable **state validation** - ensuring timeouts don't fire if the FSM has transitioned away from the timed state.

## TimeoutStrategy

Mechanoid uses a strategy pattern for timeout management. Choose the appropriate strategy for your deployment:

**Fiber-based (in-memory):**
```scala
type OrderId = String
TimeoutStrategy.fiber[OrderId]  // Fast, but doesn't survive node failures
```

**Durable (persisted):**
```scala
type OrderId = String
TimeoutStrategy.durable[OrderId]  // Requires TimeoutStore, survives node failures
```

Use `TimeoutStrategy.durable` for production deployments:

```scala
import mechanoid.*
import zio.*

enum OrderState derives Finite:
  case Pending, Started, Done

enum OrderEvent derives Finite:
  case StartPayment, Complete

import OrderState.*, OrderEvent.*

val machine = Machine(assembly[OrderState, OrderEvent](
  Pending via StartPayment to Started,
  Started via Complete to Done,
))

type OrderId = String
val orderId: OrderId = "order-1"
val eventStoreLayer: zio.ULayer[EventStore[OrderId, OrderState, OrderEvent]] =
  InMemoryEventStore.layer[OrderId, OrderState, OrderEvent]
val timeoutStoreLayer: zio.ULayer[TimeoutStore[OrderId]] =
  ZLayer.fromZIO(InMemoryTimeoutStore.make[OrderId])
```

```scala
val program = ZIO.scoped {
  for
    fsm <- FSMRuntime(orderId, machine, Pending)
    _   <- fsm.send(StartPayment)
    // Timeout is now persisted - survives node restart
  yield ()
}.provide(
  eventStoreLayer,
  timeoutStoreLayer,
  TimeoutStrategy.durable[OrderId],
  LockingStrategy.optimistic[OrderId]
)
```

## TimeoutSweeper

A background service discovers and fires expired timeouts. It integrates directly with `FSMRuntime` for type-safe timeout handling:

```scala
val config = TimeoutSweeperConfig()

val sweeper = ZIO.scoped {
  for
    timeoutStore <- ZIO.service[TimeoutStore[OrderId]]
    runtime <- FSMRuntime(orderId, machine, Pending)

    // TimeoutSweeper integrates directly with FSMRuntime
    sweeper <- TimeoutSweeper.make(config, timeoutStore, runtime)
    _ <- ZIO.never // Keep running
  yield ()
}.provide(
  eventStoreLayer,
  timeoutStoreLayer,
  TimeoutStrategy.durable[OrderId],
  LockingStrategy.optimistic[OrderId]
)
```

**Flow:**
1. Query for expired, unclaimed timeouts
2. Atomically claim each timeout (prevents duplicates)
3. **Validate FSM state**: compare current `(stateHash, sequenceNr)` with stored values
4. **If valid**: look up timeout event via `Machine.timeoutEvents(stateHash)`, fire via `runtime.send(event)`
5. **If stale** (state/seqNr changed): skip firing, increment `timeoutsSkipped` metric
6. Mark complete (removes from TimeoutStore)

**State Validation** prevents race conditions where a timeout fires after the FSM has already transitioned. The stored `sequenceNr` acts as a "generation counter" - if the FSM transitions away and back to the same state, old timeouts are correctly identified as stale.

## Sweeper Configuration

```scala
val config = TimeoutSweeperConfig()
  .withSweepInterval(Duration.fromSeconds(5))     // Base interval
  .withJitterFactor(0.2)                          // 0.0-1.0, prevents thundering herd
  .withBatchSize(100)                             // Max per sweep
  .withClaimDuration(Duration.fromSeconds(30))   // How long to hold claims
  .withBackoffOnEmpty(Duration.fromSeconds(10)) // Extra wait when idle
  .withNodeId("node-1")                           // Unique node identifier
```

**Jitter algorithm:**
```
actualWait = sweepInterval + random(0, jitterFactor * sweepInterval)
           + (backoffOnEmpty if no timeouts found)
```

## Leader Election

For reduced database load, use single-active-sweeper mode:

```scala
val leaseStore: LeaseStore = ??? // Your implementation

val config = TimeoutSweeperConfig()
  .withLeaderElection(
    LeaderElectionConfig()
      .withLeaseDuration(Duration.fromSeconds(30))
      .withRenewalInterval(Duration.fromSeconds(10))
  )

val sweeper = ZIO.scoped {
  for
    timeoutStore <- ZIO.service[TimeoutStore[OrderId]]
    runtime <- FSMRuntime(orderId, machine, Pending)
    sweeper <- TimeoutSweeper.make(config, timeoutStore, runtime, Some(leaseStore))
    _ <- ZIO.never
  yield ()
}.provide(
  eventStoreLayer,
  timeoutStoreLayer,
  TimeoutStrategy.durable[OrderId],
  LockingStrategy.optimistic[OrderId]
)
```

Only the leader node performs sweeps. If the leader fails, another node acquires the lease after expiration.

---

[<< Previous: Persistence](persistence.md) | [Back to Index](DOCUMENTATION.md) | [Next: Distributed Systems >>](distributed.md)
