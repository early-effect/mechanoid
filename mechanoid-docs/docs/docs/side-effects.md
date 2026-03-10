# Side Effects

[Back to Documentation Index](DOCUMENTATION.md)

---

Mechanoid provides two mechanisms for executing side effects when transitions occur.

## Synchronous Entry Effects

Use `.onEntry` for side effects that should run synchronously when a transition fires:

```scala mdoc:reset:silent
import mechanoid.*
import zio.*

enum OrderState derives Finite:
  case Created, Processing

enum OrderEvent derives Finite:
  case StartPayment

import OrderState.*, OrderEvent.*
```

```scala mdoc:compile-only
val machine = Machine(assembly[OrderState, OrderEvent](
  (Created via StartPayment to Processing)
    .onEntry { (event, targetState) =>
      ZIO.logInfo(s"Starting payment processing for $event")
    },
))
```

Entry effects:
- Receive `(event: E, targetState: S)`
- Run synchronously during `send()`
- Failures cause `ActionFailedError` and the transition is NOT persisted
- Use for: logging, metrics, validation, quick synchronous operations

## Producing Effects

Use `.producing` for async operations that produce events to send back to the FSM:

```scala mdoc:reset:silent
import mechanoid.*
import zio.*

enum OrderState derives Finite:
  case Processing, AwaitingResult, Succeeded, Failed

enum OrderEvent derives Finite:
  case CheckPayment(orderId: String)
  case PaymentSucceeded(txnId: String)
  case PaymentFailed(message: String)

import OrderState.*, OrderEvent.*

case class PaymentStatus(success: Boolean, txnId: String, message: String)
object paymentService:
  def checkStatus(orderId: String): ZIO[Any, Nothing, PaymentStatus] =
    ZIO.succeed(PaymentStatus(true, "txn-123", ""))
```

```scala mdoc:compile-only
val machine = Machine(assembly[OrderState, OrderEvent](
  (Processing via event[CheckPayment] to AwaitingResult)
    .producing { (event, targetState) =>
      event match
        case CheckPayment(orderId) =>
          paymentService.checkStatus(orderId).map {
            case PaymentStatus(true, txnId, _) => PaymentSucceeded(txnId)
            case PaymentStatus(false, _, msg) => PaymentFailed(msg)
          }
        case _ => ZIO.succeed(PaymentFailed("unexpected event"))
    },
  AwaitingResult via event[PaymentSucceeded] to Succeeded,
  AwaitingResult via event[PaymentFailed] to Failed,
))
```

Producing effects:
- Receive `(event: E, targetState: S)`
- Return `ZIO[Any, Any, E2]` where `E2` is an event type
- Fork as daemon fiber (fire-and-forget)
- Produced event is automatically sent to the FSM
- Errors are logged but don't fail the original transition
- Use for: external API calls, async processing, health checks

## Fault-Tolerant Patterns

Combine `.producing` with timeouts for self-healing FSMs:

```scala mdoc:reset:silent
import mechanoid.*
import zio.*

enum ServiceState derives Finite:
  case Stopped, Started, Degraded, Critical

enum ServiceEvent derives Finite:
  case Start, Stop, Heartbeat, DegradedCheck, ManualReset
  case Healthy, Unstable, Failed

import ServiceState.*, ServiceEvent.*

object HealthChecker:
  def normalCheck: ZIO[Any, Nothing, ServiceEvent] = ZIO.succeed(Healthy)
```

```scala mdoc:silent
val machine = Machine(assemblyAll[ServiceState, ServiceEvent]:
  // Start the service
  Stopped via Start to Started

  // Normal operation: heartbeat fires every 10s, triggers health check
  (Started via Heartbeat to Started)
    .onEntry { (_, _) => ZIO.logInfo("Running health check...") }
    .producing { (_, _) => HealthChecker.normalCheck }  // Returns Healthy/Unstable/Failed
    @@ Aspect.timeout(10.seconds, Heartbeat)

  // Healthy → stay started, reset timeout
  (Started via Healthy to Started)
    .onEntry { (_, _) => ZIO.logInfo("Health check: HEALTHY") }
    @@ Aspect.timeout(10.seconds, Heartbeat)

  // Unstable → enter degraded mode with faster checks (3s)
  (Started via Unstable to Degraded)
    .onEntry { (_, _) => ZIO.logWarning("Entering degraded mode") }
    @@ Aspect.timeout(3.seconds, DegradedCheck)

  // Failed → critical state, wait for human intervention
  (Started via Failed to Critical)
    .onEntry { (_, _) => ZIO.logError("Awaiting intervention") }
    @@ Aspect.timeout(15.seconds, ManualReset)
)
```

**Why this works:**
- Timeouts are durable (survive node restarts with `TimeoutStrategy.durable`)
- Health checks are fire-and-forget (don't block transition)
- Produced events drive state machine forward
- Different states = different monitoring intensity
- No external command system needed - all orchestration via events

**Production setup:**
```scala mdoc:compile-only
type InstanceId = String
val instanceId: InstanceId = "service-1"
val eventStoreLayer: zio.ULayer[EventStore[InstanceId, ServiceState, ServiceEvent]] =
  InMemoryEventStore.layer[InstanceId, ServiceState, ServiceEvent]
val timeoutStoreLayer: zio.ULayer[TimeoutStore[InstanceId]] =
  ZLayer.fromZIO(InMemoryTimeoutStore.make[InstanceId])
val config = TimeoutSweeperConfig()

val program = ZIO.scoped {
  for
    timeoutStore <- ZIO.service[TimeoutStore[InstanceId]]
    fsm <- FSMRuntime(instanceId, machine, ServiceState.Stopped)
    sweeper <- TimeoutSweeper.make(config, timeoutStore, fsm)
    _ <- fsm.send(ServiceEvent.Start)
    _ <- ZIO.never  // Keep running
  yield ()
}.provide(
  eventStoreLayer,
  timeoutStoreLayer,
  TimeoutStrategy.durable[InstanceId],
  LockingStrategy.optimistic[InstanceId]
)
```

See the `examples/heartbeat` project for a complete working example.

## Per-State Entry and Exit Effects

For effects that should run for ALL transitions entering or exiting a state (not per-transition), use `onEnter` and `onExit` on Assembly:

```scala mdoc:reset:silent
import mechanoid.*
import zio.*

enum MyState derives Finite:
  case Idle, Running, Done

enum MyEvent derives Finite:
  case Start, Finish

import MyState.*, MyEvent.*
```

```scala mdoc:compile-only
val machine = Machine(assembly[MyState, MyEvent](
  Idle via Start to Running,
  Running via Finish to Done,
).onEnter(Running) { (event, state) =>
  ZIO.logInfo(s"Entered $state via $event")
}.onExit(Running) { (event, state) =>
  ZIO.logInfo(s"Exiting $state via $event")
})
```

State effects compose through `combine()`/`++` — if a composed assembly defines `onEnter`/`onExit`, those effects are inherited by the combined assembly.

---

[<< Previous: Lock Heartbeat](lock-heartbeat.md) | [Back to Index](DOCUMENTATION.md) | [Next: Visualization >>](visualization.md)
