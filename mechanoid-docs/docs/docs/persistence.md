# Persistence

[Back to Documentation Index](DOCUMENTATION.md)

---

## Event Sourcing Model

Mechanoid supports event sourcing for durable FSMs:

1. Events are persisted *after* the transition action succeeds
2. State is reconstructed by replaying events
3. Snapshots reduce recovery time

```scala mdoc:reset:silent
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
```

```scala mdoc:compile-only
val program = ZIO.scoped {
  for
    fsm <- FSMRuntime(orderId, machine, Pending)
    _   <- fsm.send(Pay)    // Event persisted
    _   <- fsm.send(Ship)   // Event persisted
  yield ()
}.provide(
  eventStoreLayer,
  TimeoutStrategy.fiber[OrderId],
  LockingStrategy.optimistic[OrderId]
)
```


## EventStore Interface

Implement `EventStore[Id, S, E]` for your storage backend:

```scala mdoc:compile-only
import zio.stream.ZStream

trait EventStore[Id, S, E]:
  def append(instanceId: Id, event: E, expectedSeqNr: Long): ZIO[Any, MechanoidError, Long]
  def loadEvents(instanceId: Id): ZStream[Any, MechanoidError, StoredEvent[Id, E]]
  def loadEventsFrom(instanceId: Id, fromSeqNr: Long): ZStream[Any, MechanoidError, StoredEvent[Id, E]]
  def loadSnapshot(instanceId: Id): ZIO[Any, MechanoidError, Option[FSMSnapshot[Id, S]]]
  def saveSnapshot(snapshot: FSMSnapshot[Id, S]): ZIO[Any, MechanoidError, Unit]
  def highestSequenceNr(instanceId: Id): ZIO[Any, MechanoidError, Long]
```

**Critical**: `append` must implement optimistic locking - atomically check that `expectedSeqNr` matches the current highest sequence number, then increment. This prevents lost updates in concurrent scenarios.

## Snapshots

Snapshots capture point-in-time state to speed up recovery:

```scala mdoc:compile-only
val program = ZIO.scoped {
  for
    fsm <- FSMRuntime(orderId, machine, Pending)

    // Manual snapshot (you control when)
    _ <- fsm.saveSnapshot

    // Example strategies:
    // After every N events
    seqNr <- fsm.lastSequenceNr
    _ <- ZIO.when(seqNr % 100 == 0)(fsm.saveSnapshot)

    // On specific states
    state <- fsm.currentState
    _ <- ZIO.when(state == Shipped)(fsm.saveSnapshot)
  yield ()
}.provide(
  eventStoreLayer,
  TimeoutStrategy.fiber[OrderId],
  LockingStrategy.optimistic[OrderId]
)
```

## Recovery

On startup, `FSMRuntime` (when provided with an EventStore):

1. Loads the latest snapshot (if any)
2. Replays only events *after* the snapshot's sequence number
3. Resumes normal operation

Recovery time is proportional to events since the last snapshot, not total events.

## Optimistic Locking

The persistence layer uses optimistic locking to detect concurrent modifications:

```scala mdoc:compile-only
// If another process modified the FSM between read and write:
val error = SequenceConflictError(instanceId = orderId, expectedSeqNr = 5, actualSeqNr = 6)
```

This error indicates a concurrent modification. The caller should reload state and retry.

---

[<< Previous: Running FSMs](running-fsms.md) | [Back to Index](DOCUMENTATION.md) | [Next: Durable Timeouts >>](durable-timeouts.md)
