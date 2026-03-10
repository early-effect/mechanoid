# Distributed Systems

[Back to Documentation Index](DOCUMENTATION.md)

---

## Distributed Architecture

### Load-on-Demand Model

Mechanoid uses a **database as source of truth** pattern rather than in-memory cluster coordination. This means:

- **Nodes don't notify each other** - There's no pub/sub or cluster membership
- **State lives in the database** - The EventStore is the single source of truth
- **Load fresh on each operation** - FSMs are loaded from the database when needed
- **Stateless application nodes** - Any node can handle any FSM instance

```
Node A                    EventStore (DB)                 Node B
   │                           │                            │
   │── load events ───────────>│                            │
   │<── [event1, event2] ──────│                            │
   │                           │                            │
   │   (process event)         │                            │
   │                           │                            │
   │── append(event3, seq=2) ─>│                            │
   │<── success (seq=3) ───────│                            │
   │                           │                            │
   │                           │<── load events ────────────│
   │                           │──> [event1, event2, event3]│
```

### When Does a Node See Updates?

A node sees updates when it **next loads the FSM from the database**:

| Scenario | What Happens |
|----------|--------------|
| New request arrives | Loads latest events from EventStore |
| FSM already in scope | Uses cached state until scope closes |
| Timeout sweeper fires | Loads FSM fresh, checks state, fires if valid |
| After scope closes | Next request loads fresh state |

### Design Benefits

This architecture provides several advantages:

1. **Simplicity** - No cluster coordination protocol needed
2. **Horizontal scaling** - Add nodes without configuration changes
3. **Fault tolerance** - Node failures don't affect other nodes
4. **Consistency** - Database provides strong consistency guarantees
5. **Debugging** - All state changes are in the EventStore

### Conflict Handling

When two nodes try to modify the same FSM concurrently:

1. **Optimistic locking (always active)** - Sequence numbers detect conflicts at write time
2. **Distributed locking (optional)** - Prevents conflicts before they happen

```scala
import mechanoid.*
import zio.*

type OrderId = String
val orderId: OrderId = "order-1"
```

```scala
// Without distributed locking: conflict detected at write time
val error = SequenceConflictError(instanceId = orderId, expectedSeqNr = 5, actualSeqNr = 6)

// With distributed locking: conflict prevented upfront
LockingStrategy.distributed[OrderId]  // Acquires lock before each transition
```

---

## Distributed Locking

### Why Use Locking

Without locking, concurrent event processing for the same FSM instance relies on **optimistic locking** (sequence numbers), which detects conflicts *after* they happen:

1. Node A reads FSM state (seqNr = 5)
2. Node B reads FSM state (seqNr = 5)
3. Both process events concurrently
4. Node A writes (seqNr → 6) - succeeds
5. Node B writes (seqNr → 6) - fails with `SequenceConflictError`

This leads to:
- Wasted work (rejected processing)
- Retry overhead
- Potential confusion about "who won"

With **distributed locking**, conflicts are *prevented* rather than detected:

1. Node A acquires lock for FSM instance
2. Node B tries to acquire - waits or fails fast
3. Node A processes event, releases lock
4. Node B acquires lock, processes its event

### FSMInstanceLock

Implement `FSMInstanceLock[Id]` for your distributed lock backend:

```scala
import java.time.Instant

trait FSMInstanceLock[Id]:
  def tryAcquire(instanceId: Id, nodeId: String, duration: Duration, now: Instant): ZIO[Any, Throwable, LockResult[Id]]
  def acquire(instanceId: Id, nodeId: String, duration: Duration, timeout: Duration): ZIO[Any, Throwable, LockResult[Id]]
  def release(token: LockToken[Id]): ZIO[Any, Throwable, Boolean]
  def extend(token: LockToken[Id], additionalDuration: Duration, now: Instant): ZIO[Any, Throwable, Option[LockToken[Id]]]
  def get(instanceId: Id, now: Instant): ZIO[Any, Throwable, Option[LockToken[Id]]]
```

### LockingStrategy

Mechanoid uses a strategy pattern for concurrency control. Choose the appropriate strategy for your deployment:

**Optimistic (default):**
```scala
type OrderId = String
LockingStrategy.optimistic[OrderId]  // Relies on EventStore sequence conflict detection
```

**Distributed:**
```scala
type OrderId = String
LockingStrategy.distributed[OrderId]  // Acquires exclusive lock before each transition
```

Use `LockingStrategy.distributed` for high-contention production deployments:

```scala
import mechanoid.*
import zio.*

enum OrderState derives Finite:
  case Pending, Paid, Shipped

enum OrderEvent derives Finite:
  case Pay, Ship

import OrderState.*, OrderEvent.*

val machine = Machine(assembly[OrderState, OrderEvent](
  Pending via Pay to Paid,
  Paid via Ship to Shipped,
))

type OrderId = String
val orderId: OrderId = "order-1"
val eventStoreLayer: zio.ULayer[EventStore[OrderId, OrderState, OrderEvent]] =
  InMemoryEventStore.layer[OrderId, OrderState, OrderEvent]
val lockServiceLayer: zio.ULayer[FSMInstanceLock[OrderId]] =
  ZLayer.fromZIO(InMemoryFSMInstanceLock.make[OrderId])
```

```scala
val program = ZIO.scoped {
  for
    fsm <- FSMRuntime(orderId, machine, Pending)
    _   <- fsm.send(Pay)  // Lock acquired automatically before processing
  yield ()
}.provide(
  eventStoreLayer,
  lockServiceLayer,                     // FSMInstanceLock implementation
  TimeoutStrategy.fiber[OrderId],
  LockingStrategy.distributed[OrderId]  // Prevents concurrent modifications
)
```

### Lock Configuration

```scala
val config = LockConfig()
  .withLockDuration(Duration.fromSeconds(30))    // How long to hold locks
  .withAcquireTimeout(Duration.fromSeconds(10))  // Max wait when acquiring
  .withRetryInterval(Duration.fromMillis(100))   // Retry frequency when busy
  .withValidateBeforeOperation(true)             // Double-check lock before each op
  .withNodeId("node-1")                          // Unique node identifier
```

**Preset configurations:**

```scala
LockConfig.default      // 30s duration, 10s timeout
LockConfig.fast         // 10s duration, 5s timeout (for quick operations)
LockConfig.longRunning  // 5 min duration, 30s timeout (for batch jobs)
```

### Node Failure Resilience

Locks are **lease-based** and automatically expire. This handles several failure scenarios:

| Scenario | What Happens |
|----------|--------------|
| Node crash | Lock expires after `lockDuration`, other nodes proceed |
| Network partition | Same as crash - lock expires |
| Long GC pause | If pause exceeds `lockDuration`, lock expires |

**Zombie Node Protection:**

Even if a paused node wakes up after its lock expired:

1. **Lock validation**: If `validateBeforeOperation` is enabled, the node checks if it still holds the lock before writing
2. **EventStore optimistic locking**: Even if the zombie writes, `SequenceConflictError` is raised because another node already incremented the sequence number

**PostgreSQL Implementation:**

```sql
CREATE TABLE fsm_instance_locks (
  instance_id  TEXT PRIMARY KEY,
  node_id      TEXT NOT NULL,
  acquired_at  TIMESTAMPTZ NOT NULL,
  expires_at   TIMESTAMPTZ NOT NULL
);

-- Atomic acquire (succeeds if expired or same node)
INSERT INTO fsm_instance_locks (instance_id, node_id, acquired_at, expires_at)
VALUES ($1, $2, NOW(), NOW() + $3::interval)
ON CONFLICT (instance_id) DO UPDATE
  SET node_id = EXCLUDED.node_id,
      acquired_at = EXCLUDED.acquired_at,
      expires_at = EXCLUDED.expires_at
  WHERE fsm_instance_locks.expires_at < NOW()
     OR fsm_instance_locks.node_id = EXCLUDED.node_id
RETURNING *;
```

### Combining Features

For maximum robustness, combine distributed locking with durable timeouts:

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
val lockServiceLayer: zio.ULayer[FSMInstanceLock[OrderId]] =
  ZLayer.fromZIO(InMemoryFSMInstanceLock.make[OrderId])
```

```scala
val program = ZIO.scoped {
  for
    fsm <- FSMRuntime(orderId, machine, Pending)
    _   <- fsm.send(StartPayment)
    // - Lock ensures exactly-once processing
    // - Timeout persisted and survives node restart
  yield ()
}.provide(
  eventStoreLayer,
  timeoutStoreLayer,
  lockServiceLayer,
  TimeoutStrategy.durable[OrderId],       // Timeouts survive node failures
  LockingStrategy.distributed[OrderId]    // Prevents concurrent modifications
)
```

This provides:
- **Exactly-once transitions** via distributed locking
- **Durable timeouts** that survive node failures
- **Optimistic locking** as a final safety net (always active via EventStore)

---

[<< Previous: Durable Timeouts](durable-timeouts.md) | [Back to Index](DOCUMENTATION.md) | [Next: Lock Heartbeat >>](lock-heartbeat.md)
