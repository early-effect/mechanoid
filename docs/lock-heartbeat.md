# Lock Heartbeat and Atomic Transitions

[Back to Documentation Index](DOCUMENTATION.md)

---

## Automatic Lock Renewal

For operations that may take longer than the initial lock duration, use `withLockAndHeartbeat` which automatically renews the lock in the background:

```scala
import mechanoid.*
import zio.*

type OrderId = String
val orderId: OrderId = "order-1"
```

```scala
def processOrder(orderId: String): ZIO[Any, Nothing, Unit] = ZIO.unit

val lock: FSMInstanceLock[OrderId] = ???
val nodeId = "node-1"

val heartbeatConfig = LockHeartbeatConfig(
  renewalInterval = Duration.fromSeconds(10),
  renewalDuration = Duration.fromSeconds(30),
  jitterFactor = 0.1,
  onLockLost = LockLostBehavior.FailFast,
)

lock.withLockAndHeartbeat(orderId, nodeId, Duration.fromSeconds(30), heartbeat = heartbeatConfig) {
  // Long-running operation - lock is automatically renewed
  processOrder(orderId)
}
```

**Configuration:**

| Parameter | Description | Recommendation |
|-----------|-------------|----------------|
| `renewalInterval` | How often to renew | ≤ `renewalDuration / 3` |
| `renewalDuration` | Lock duration on each renewal | Same as or longer than initial duration |
| `jitterFactor` | Random jitter (0.0-1.0) | 0.1 to prevent thundering herd |
| `onLockLost` | Behavior when renewal fails | `FailFast` for safety |

## Lock Lost Behavior

When the heartbeat fails to renew the lock, there are two behaviors:

**FailFast (Default - Safe):**
```scala
val behavior = LockLostBehavior.FailFast
```
The main effect is interrupted immediately. Use for non-idempotent operations where another node may have acquired the lock.

**Continue (Use with caution):**
```scala
val behavior = LockLostBehavior.Continue(
  ZIO.logWarning("Lock lost but continuing...")
)
```
Runs the provided effect, then continues execution. Only use for idempotent operations where completing is more important than safety.

## Atomic Transitions

Use `withAtomicTransitions` on `LockedFSMRuntime` to execute multiple FSM transitions while holding a single lock with automatic renewal:

```scala
import mechanoid.*
import zio.*

enum OrderState derives Finite:
  case Pending, Validated, Approved, AutoApproved

  def needsApproval: Boolean = this == Validated

enum OrderEvent derives Finite:
  case ValidateOrder, RequestApproval, AutoApprove

import OrderState.*, OrderEvent.*

val machine = Machine(assembly[OrderState, OrderEvent](
  Pending via ValidateOrder to Validated,
  Validated via RequestApproval to Approved,
  Validated via AutoApprove to AutoApproved,
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
    lock <- ZIO.service[FSMInstanceLock[OrderId]]
    lockedFsm = LockedFSMRuntime(fsm, lock)
    _ <- lockedFsm.withAtomicTransitions() { ctx =>
      for
        outcome1 <- ctx.send(ValidateOrder)      // First transition
        state    <- ctx.currentState
        _        <- if state.current.needsApproval
                    then ctx.send(RequestApproval) // Conditional transition
                    else ctx.send(AutoApprove)     // Alternative transition
      yield ()
    }
  yield ()
  // Side effects from .producing are handled asynchronously as fire-and-forget
}.provide(
  eventStoreLayer,
  lockServiceLayer,
  TimeoutStrategy.fiber[OrderId],
  LockingStrategy.distributed[OrderId]
)
```

**When to Use:**
- Conditional transitions based on intermediate state
- Saga-like patterns where multiple events form one logical operation
- Reading state between transitions for branching logic

## Anti-Patterns to Avoid

**WRONG - Don't do long-running work inside atomic transactions:**
```scala
def callExternalPaymentAPI(): ZIO[Any, Nothing, Unit] = ZIO.unit

val badProgram = ZIO.scoped {
  for
    fsm <- FSMRuntime(orderId, machine, Pending)
    lock <- ZIO.service[FSMInstanceLock[OrderId]]
    lockedFsm = LockedFSMRuntime(fsm, lock)
    _ <- lockedFsm.withAtomicTransitions() { ctx =>
      for
        _ <- ctx.send(ValidateOrder)
        _ <- callExternalPaymentAPI()  // Should use .producing instead!
        _ <- ctx.send(RequestApproval)
      yield ()
    }
  yield ()
}.provide(
  eventStoreLayer,
  lockServiceLayer,
  TimeoutStrategy.fiber[OrderId],
  LockingStrategy.distributed[OrderId]
)
```

**RIGHT - Fast orchestration with side effects via .producing:**
```scala
import mechanoid.*
import zio.*

enum OrderState derives Finite:
  case Processing, AwaitingResult, Succeeded, Failed

enum OrderEvent derives Finite:
  case CheckStatus
  case PaymentSucceeded(txnId: String)
  case PaymentFailed(reason: String)

import OrderState.*, OrderEvent.*

case class PaymentResult(success: Boolean, txnId: String, reason: String)
def callExternalPaymentAPI(): ZIO[Any, Nothing, PaymentResult] =
  ZIO.succeed(PaymentResult(true, "txn-123", ""))
```

```scala
// Define transition with producing effect
val machine = Machine(assembly[OrderState, OrderEvent](
  (Processing via CheckStatus to AwaitingResult)
    .producing { (_, _) =>
      // This runs asynchronously after the transition
      callExternalPaymentAPI().map {
        case PaymentResult(true, txnId, _) => PaymentSucceeded(txnId)
        case PaymentResult(false, _, reason) => PaymentFailed(reason)
      }
    },
  AwaitingResult via event[PaymentSucceeded] to Succeeded,
  AwaitingResult via event[PaymentFailed] to Failed,
))

// In atomic transaction, just send the event - side effect runs asynchronously
val goodProgram = ZIO.scoped {
  for
    fsm <- machine.start(Processing)
    _ <- fsm.send(CheckStatus)
    // Lock released quickly, external API call runs in background
  yield ()
}
```

## Best Practices for Side Effects

Lock heartbeat and atomic transitions are for **fast orchestration** - quickly deciding what needs to happen and sending events. Long-running work should be handled via `.producing` effects:

| Concern | Handled By |
|---------|------------|
| Multiple FSM transitions atomically | `withAtomicTransitions` |
| Long-running external calls | `.producing` effects (fire-and-forget) |
| Synchronous logging/metrics | `.onEntry` effects |
| Lock renewal during orchestration | `LockHeartbeatConfig` |

This separation provides:
- **Fast lock release** - Locks are held only for state changes, not I/O
- **Non-blocking side effects** - `.producing` effects run as daemon fibers
- **Self-driving FSMs** - Produced events automatically sent back to FSM

---

[<< Previous: Distributed Systems](distributed.md) | [Back to Index](DOCUMENTATION.md) | [Next: Side Effects >>](side-effects.md)
