# Reference

[Back to Documentation Index](DOCUMENTATION.md)

---

## Error Handling

| Error | Cause |
|-------|-------|
| `InvalidTransitionError(state, event)` | No transition defined for state/event combination |
| `FSMStoppedError(reason)` | FSM has been stopped |
| `ProcessingTimeoutError(state, duration)` | Timeout during event processing |
| `ActionFailedError(cause)` | User-defined error from lifecycle action |
| `PersistenceError(cause)` | Persistence operation failed |
| `SequenceConflictError(expected, actual, instanceId)` | Concurrent modification detected |
| `EventReplayError(state, event, sequenceNr)` | Stored event doesn't match FSM definition |
| `LockingError(cause)` | Distributed lock operation failed (busy, timeout, etc.) |

---

## Complete Example

```scala mdoc:reset:silent
import mechanoid.*
import zio.*
import java.time.Instant

// Domain - plain enums with Finite derivation
enum OrderState derives Finite:
  case Pending, AwaitingPayment, Paid, Shipped, Delivered, Cancelled

enum OrderEvent derives Finite:
  case Create, RequestPayment, ConfirmPayment, Ship, Deliver, Cancel, PaymentTimeout

import OrderState.*, OrderEvent.*

// Simulated services
object PaymentService:
  def charge(amount: BigDecimal): ZIO[Any, Nothing, Unit] =
    ZIO.logInfo(s"Charging $$${amount}")

object WarehouseService:
  def notify(orderId: String): ZIO[Any, Nothing, Unit] =
    ZIO.logInfo(s"Notifying warehouse for order $orderId")

object EmailService:
  def send(to: String, template: String): ZIO[Any, Nothing, Unit] =
    ZIO.logInfo(s"Sending $template email to $to")
```

```scala mdoc:compile-only
// FSM Definition with side effects
val orderMachine = Machine(
  assembly[OrderState, OrderEvent](
    // Happy path with timeout on entry to AwaitingPayment
    (Pending via RequestPayment to AwaitingPayment) @@ Aspect.timeout(30.minutes, PaymentTimeout),

    (AwaitingPayment via ConfirmPayment to Paid)
      .onEntry { (_, _) =>
        PaymentService.charge(BigDecimal(100))
      },

    (Paid via Ship to Shipped)
      .onEntry { (_, _) =>
        WarehouseService.notify("order-123")
      },

    (Shipped via Deliver to Delivered)
      .onEntry { (_, _) =>
        EmailService.send("customer@example.com", "delivered")
      },

    // Timeout handling
    AwaitingPayment via PaymentTimeout to Cancelled,

    // Cancellation from multiple states
    anyOf(Pending, AwaitingPayment) via Cancel to Cancelled,
    // Per-state effects: run for ALL transitions entering these states
  ).onEnter(AwaitingPayment) { (_, _) =>
    ZIO.logInfo("Waiting for payment...")
  }.onEnter(Cancelled) { (_, _) =>
    ZIO.logInfo("Order cancelled")
  }
)

// Running with persistence and durable timeouts
type OrderId = String
val eventStoreLayer: zio.ULayer[EventStore[OrderId, OrderState, OrderEvent]] =
  InMemoryEventStore.layer[OrderId, OrderState, OrderEvent]
val timeoutStoreLayer: zio.ULayer[TimeoutStore[OrderId]] =
  ZLayer.fromZIO(InMemoryTimeoutStore.make[OrderId])

val program = ZIO.scoped {
  for
    // Create FSM - strategies are provided via ZIO environment
    fsm <- FSMRuntime("order-123", orderMachine, OrderState.Pending)

    // Process order
    _ <- fsm.send(OrderEvent.RequestPayment)

    // Wait for payment (will timeout after 30 minutes if not received)
    // Even if this node crashes, another node's sweeper will fire the timeout

    // Check current state
    state <- fsm.currentState
    _ <- ZIO.logInfo(s"Current state: $state")

  yield ()
}.provide(
  eventStoreLayer,
  timeoutStoreLayer,
  TimeoutStrategy.durable[String],      // Durable timeouts survive node failures
  LockingStrategy.optimistic[String]    // Or use LockingStrategy.distributed for high contention
)

// For long-running processes with sweeper, run alongside FSMRuntime:
// See examples/heartbeat for a complete working example
```

---

## Dependencies

```scala
libraryDependencies ++= Seq(
  "dev.zio" %% "zio"         % "2.1.24",
  "dev.zio" %% "zio-streams" % "2.1.24",
  "dev.zio" %% "zio-json"    % "0.7.42"  // For JSON serialization in persistence
)
```

For PostgreSQL persistence with [Saferis](https://github.com/russwyte/saferis):

```scala
libraryDependencies ++= Seq(
  "io.github.russwyte" %% "saferis"       % "0.1.1",
  "org.postgresql"      % "postgresql"    % "42.7.8"
)
```

---

[<< Previous: Visualization](visualization.md) | [Back to Index](DOCUMENTATION.md)
