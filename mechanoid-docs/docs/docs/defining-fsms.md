# Defining FSMs

[Back to Documentation Index](DOCUMENTATION.md)

---

## Basic Definition

Create FSM definitions using `assembly` to define transitions and `Machine` to make them runnable:

```scala mdoc:reset:silent
import mechanoid.*
import zio.*

enum MyState derives Finite:
  case State1, State2, State3

enum MyEvent derives Finite:
  case Event1, Event2, Event3

import MyState.*, MyEvent.*
```

```scala mdoc:compile-only
val machine = Machine(assembly[MyState, MyEvent](
  State1 via Event1 to State2,
  State1 via Event2 to stay,
  State2 via Event3 to State3,
))
```

The `assembly` macro performs compile-time validation of transitions, and `Machine(assembly)` creates the runnable FSM.

## Compile-Time Safety

Mechanoid leverages Scala 3 macros to catch errors at compile time rather than runtime. This section documents all compile-time guarantees.

### Type Safety: Finite Derivation

States and events must derive `Finite` to be used in an FSM. The macro validates:

1. **Sealed requirement** - Type must be `sealed trait`, `sealed class`, or `enum`
2. **Non-empty cases** - Must have at least one case

```scala mdoc:fail
// Non-sealed types fail compilation:
trait NotSealed derives Finite
```

```scala mdoc:fail
// Sealed types with no cases fail:
sealed trait EmptySealed derives Finite
```

### Duplicate Transition Detection

The `assembly` macro detects duplicate transitions at compile time:

```scala mdoc:fail
import mechanoid.*
enum DupState derives Finite:
  case S1, S2, S3
enum DupEvent derives Finite:
  case E1
import DupState.*, DupEvent.*
// This will fail at compile time:
val bad = assembly[DupState, DupEvent](
  S1 via E1 to S2,
  S1 via E1 to S3,  // Error: Duplicate transition for S1 + E1
)
```

### Override Validation

To intentionally override a transition (e.g., after using `all[T]`), use `@@ Aspect.overriding`:

```scala mdoc:reset:silent
import mechanoid.*

sealed trait MyState2 derives Finite

sealed trait Processing extends MyState2 derives Finite
case object SpecialState extends Processing
case object RegularState extends Processing

case object Cancelled extends MyState2
case object Special extends MyState2

enum MyEvent2 derives Finite:
  case Cancel

import MyEvent2.*
```

```scala mdoc:compile-only
val machine = Machine(assembly[MyState2, MyEvent2](
  all[Processing] via Cancel to Cancelled,
  (SpecialState via Cancel to Special) @@ Aspect.overriding,  // OK: Intentional override
))
```

**Orphan Override Warnings:**

If you mark a transition with `@@ Aspect.overriding` but there's nothing to override, the compiler emits a warning:

```scala mdoc:reset:silent
import mechanoid.*
import zio.*

enum MyState derives Finite:
  case State1, State2

enum MyEvent derives Finite:
  case Event1

import MyState.*, MyEvent.*
```

```scala mdoc:compile-only
// This produces a compile-time warning about orphan override:
val machine = Machine(assembly[MyState, MyEvent](
  (State1 via Event1 to State2) @@ Aspect.overriding,  // Warning: no duplicate to override
))
// Compiler emits: MyState.State1 via MyEvent.Event1: marked @@ Aspect.overriding but no duplicate to override
```

This helps catch typos or refactoring issues where an override becomes orphaned.

### Assembly Inline Requirement

For orphan override detection to work, assemblies must be passed inline to `Machine()`:

```scala mdoc:fail
import mechanoid.*
enum InlState derives Finite:
  case IS1, IS2
enum InlEvent derives Finite:
  case IE1
import InlState.*, InlEvent.*
// Using a val prevents orphan detection - this fails:
val myAssembly = assembly[InlState, InlEvent](
  IS1 via IE1 to IS2
)
Machine(myAssembly)  // Error: Assembly must be passed inline
```

Use `inline def` if you need to store an assembly:

```scala mdoc:reset:silent
import mechanoid.*
enum MyState derives Finite:
  case State1, State2
enum MyEvent derives Finite:
  case Event1
import MyState.*, MyEvent.*
```

```scala mdoc:compile-only
inline def myAssembly = assembly[MyState, MyEvent](
  State1 via Event1 to State2
)
Machine(myAssembly)  // OK: inline def preserves the expression
```

### Produced Event Type Validation

The `.producing` effect must return an event type that's part of the FSM's event hierarchy:

```scala mdoc:reset:silent
import mechanoid.*
import zio.*

enum MyState derives Finite:
  case A, B

enum MyEvent derives Finite:
  case E1
  case Produced

case class UnrelatedEvent(msg: String)

import MyState.*, MyEvent.*
```

```scala mdoc:compile-only
// OK: Produced is part of MyEvent
val good = assembly[MyState, MyEvent](
  (A via E1 to B).producing { (_, _) => ZIO.succeed(Produced) }
)
```

```scala mdoc:fail
import mechanoid.*
import zio.*
enum ProdState derives Finite:
  case PS1, PS2
enum ProdEvent derives Finite:
  case PE1
  case Produced
case class BadEvent(msg: String)
import ProdState.*, ProdEvent.*
// Error: BadEvent is not part of ProdEvent hierarchy
val bad = assembly[ProdState, ProdEvent](
  (PS1 via PE1 to PS2).producing { (_, _) => ZIO.succeed(BadEvent("oops")) }
)
```

## The assemblyAll Block Syntax

For more complex definitions with local helper values, use `assemblyAll`:

```scala mdoc:reset:silent
import mechanoid.*
import zio.*

enum OrderState derives Finite:
  case Created, PaymentProcessing, Paid, Cancelled

enum OrderEvent derives Finite:
  case InitiatePayment(orderId: String, amount: BigDecimal)
  case PaymentSucceeded
  case PaymentFailed

import OrderState.*, OrderEvent.*
```

```scala mdoc:compile-only
val machine = Machine(assemblyAll[OrderState, OrderEvent]:
  // Local helper vals at the top
  val logPaymentStart: (OrderEvent, OrderState) => ZIO[Any, Nothing, Unit] = { (event, _) =>
    event match
      case e: InitiatePayment => ZIO.logInfo(s"Processing payment for ${e.orderId}: ${e.amount}")
      case _ => ZIO.unit
  }

  // Transitions use the helpers
  (Created via event[InitiatePayment] to PaymentProcessing).onEntry(logPaymentStart)
  PaymentProcessing via PaymentSucceeded to Paid
  PaymentProcessing via PaymentFailed to Cancelled
)
```

The `assemblyAll` block allows mixing val definitions with transition expressions. The vals are available for use in `.onEntry` effects and other parts of the definition. No commas are needed between transition specs.

## Timeouts

Mechanoid provides a flexible timeout strategy where you define your own timeout events. This gives you complete control over timeout handling and enables powerful patterns.

### Basic Timeout Usage

```scala mdoc:reset:silent
import mechanoid.*
import zio.*

enum OrderState derives Finite:
  case Created, WaitingForPayment, Confirmed, Cancelled

enum OrderEvent derives Finite:
  case Pay, Paid, PaymentTimeout  // User-defined timeout event

import OrderState.*, OrderEvent.*
```

```scala mdoc:compile-only
// Apply timeout to the transition - when entering WaitingForPayment, a timeout is scheduled
val machine = Machine(assembly[OrderState, OrderEvent](
  (Created via Pay to WaitingForPayment) @@ Aspect.timeout(30.minutes, PaymentTimeout),
  WaitingForPayment via PaymentTimeout to Cancelled,  // Handle timeout
  WaitingForPayment via Paid to Confirmed,            // Or complete before timeout
))
```

The `@@ Aspect.timeout(duration, event)` syntax on transitions:
1. Schedules a timeout when the FSM enters the state
2. Fires the specified event when the timeout expires
3. Cancels the timeout if another event is processed first

### Multiple Timeout Events

A key feature is that **different states can use different timeout events**. This enables rich timeout handling:

```scala mdoc:reset:silent
import mechanoid.*
import zio.*

enum OrderState derives Finite:
  case Created, PaymentPending, ShipmentPending, Delivered, Cancelled, Refunded

enum OrderEvent derives Finite:
  case Pay, Ship, Deliver, Confirm
  case PaymentTimeout     // Fired after 30 minutes in PaymentPending
  case ShipmentTimeout    // Fired after 7 days in ShipmentPending

import OrderState.*, OrderEvent.*
```

```scala mdoc:compile-only
val machine = Machine(assembly[OrderState, OrderEvent](
  (Created via Pay to PaymentPending) @@ Aspect.timeout(30.minutes, PaymentTimeout),
  PaymentPending via PaymentTimeout to Cancelled,
  (PaymentPending via Confirm to ShipmentPending) @@ Aspect.timeout(7.days, ShipmentTimeout),
  ShipmentPending via ShipmentTimeout to Refunded,
  ShipmentPending via Ship to Delivered,
))
```

### Timeout Events with Data

Since timeout events are regular events in your enum, they can carry data:

```scala mdoc:reset:silent
import mechanoid.*
import zio.*
import java.time.Instant

enum SessionState derives Finite:
  case Idle, Active, Expired

enum SessionEvent derives Finite:
  case Login
  case IdleTimeout(lastActivity: Instant)     // Carries the last activity time
  case AbsoluteTimeout(sessionStart: Instant) // Carries session start time

import SessionState.*, SessionEvent.*
```

```scala mdoc:compile-only
// Timeout events can carry data - useful for logging/debugging
val machine = Machine(assembly[SessionState, SessionEvent](
  (Idle via Login to Active) @@ Aspect.timeout(15.minutes, IdleTimeout(Instant.now())),
  Active via event[IdleTimeout] to Expired,
))
```

### Different Outcomes for Same State

You can have multiple timeout types affecting the same state with different outcomes:

```scala mdoc:reset:silent
import mechanoid.*
import zio.*

enum AuctionState derives Finite:
  case Pending, Bidding, Extended, Sold

enum AuctionEvent derives Finite:
  case Bid(amount: BigDecimal)
  case StartAuction
  case ExtensionTimeout   // Short timeout - extends auction on late bids
  case FinalTimeout       // Long timeout - auction ends

import AuctionState.*, AuctionEvent.*
```

```scala mdoc:compile-only
val machine = Machine(assembly[AuctionState, AuctionEvent](
  // Start auction with 5-minute extension timeout
  (Pending via StartAuction to Bidding) @@ Aspect.timeout(5.minutes, ExtensionTimeout),

  // Late bid resets the 5-minute timer
  (Bidding via event[Bid] to Bidding) @@ Aspect.timeout(5.minutes, ExtensionTimeout),
  // Extension timeout moves to final phase with 1-minute timer
  (Bidding via ExtensionTimeout to Extended) @@ Aspect.timeout(1.minute, FinalTimeout),

  // Final phase: bid resets 1-minute timer
  (Extended via event[Bid] to Extended) @@ Aspect.timeout(1.minute, FinalTimeout),
  Extended via FinalTimeout to Sold,  // Auction ends
))
```

### Why User-Defined Timeout Events?

This design provides several advantages over a built-in `Timeout` singleton:

1. **Type safety** - Different timeouts are distinct types, preventing mix-ups
2. **Rich handling** - Each timeout can trigger different transitions and side effects
3. **Data carrying** - Timeout events can include context (timestamps, reason codes)
4. **Clear intent** - Reading `PaymentTimeout` is clearer than `Timeout`
5. **Event sourcing** - All timeout events are persisted like regular events

## Assembly Composition

Use `assembly` to create reusable transition fragments that can be composed with `combine()` or `++`, with full compile-time validation:

```scala mdoc:reset:silent
import mechanoid.*
import zio.*

sealed trait DocumentState derives Finite
case object Draft extends DocumentState
case object PendingReview extends DocumentState
case object UnderReview extends DocumentState
case object Cancelled extends DocumentState

sealed trait InReview extends DocumentState derives Finite
case object ReviewInProgress extends InReview
case object AwaitingFeedback extends InReview

sealed trait Approval extends DocumentState derives Finite
case object PendingApproval extends Approval
case object ApprovalGranted extends Approval

enum DocumentEvent derives Finite:
  case CancelReview, Abandon, SubmitForReview, AssignReviewer

import DocumentEvent.*
```

```scala mdoc:compile-only
// Compose assemblies using ++ operator
val fullWorkflow = Machine(
  assembly[DocumentState, DocumentEvent](
    all[InReview] via CancelReview to Draft,
    all[Approval] via Abandon to Cancelled,
  ) ++ assembly[DocumentState, DocumentEvent](
    Draft via SubmitForReview to PendingReview,
    PendingReview via AssignReviewer to UnderReview,
  )
)

// Or use the combine() function
val fullWorkflow2 = Machine(
  combine(
    assembly[DocumentState, DocumentEvent](
      all[InReview] via CancelReview to Draft,
      all[Approval] via Abandon to Cancelled,
    ),
    assembly[DocumentState, DocumentEvent](
      Draft via SubmitForReview to PendingReview,
      PendingReview via AssignReviewer to UnderReview,
    ),
  )
)
```

**Key difference between `Assembly` and `Machine`:**
- `Assembly` is a reusable fragment that **cannot be run** directly
- `Machine(assembly)` creates a complete `Machine` that **can be run**

**Inline Requirement for Composition:**

Both sides of `combine()`/`++` must be inline `assembly(...)` expressions or `inline def` references. This ensures the macro can inspect both assemblies for duplicates at compile time:

```scala
// Use inline def to store assemblies for reuse
inline def cancelable = assembly[DocumentState, DocumentEvent](
  all[InReview] via CancelReview to Draft,
  all[Approval] via Abandon to Cancelled,
)

val fullWorkflow = Machine(cancelable ++ assembly[DocumentState, DocumentEvent](
  Draft via SubmitForReview to PendingReview,
  PendingReview via AssignReviewer to UnderReview,
))
```

**Duplicate Detection:**

Mechanoid detects duplicate transitions (same state + event combination) at compile time in all scenarios:

| Scenario | Detection | When |
|----------|-----------|------|
| Inline specs (`A via E to B`) | **Compile time** | Macro can inspect AST |
| Same val used twice | **Compile time** | Symbol tracking |
| Assembly composition with `combine`/`++` | **Compile time** | Hash infos extracted at macro expansion |

```scala mdoc:fail
import mechanoid.*
enum Dup1S derives Finite:
  case D1A, D1B, D1C
enum Dup1E derives Finite:
  case D1E1
import Dup1S.*, Dup1E.*
// Compile ERROR within assembly
val bad = assembly[Dup1S, Dup1E](
  D1A via D1E1 to D1B,
  D1A via D1E1 to D1C,  // Compile ERROR: duplicate transition
)
```

```scala mdoc:fail
import mechanoid.*
enum Dup2S derives Finite:
  case A, B, C
enum Dup2E derives Finite:
  case E1
import Dup2S.*, Dup2E.*
// Compile ERROR across assemblies with ++
val bad = assembly[Dup2S, Dup2E](A via E1 to B) ++
  assembly[Dup2S, Dup2E](A via E1 to C)  // ERROR: duplicate A via E1
```

```scala mdoc:reset:silent
import mechanoid.*
enum S derives Finite:
  case A, B, C
enum E derives Finite:
  case E1
import S.*, E.*
```

```scala mdoc:compile-only
// Use @@ Aspect.overriding to allow intentional overrides at the transition level
val machine = Machine(
  assembly[S, E](A via E1 to B) ++
    assembly[S, E]((A via E1 to C) @@ Aspect.overriding)  // OK: override resolves duplicate
)
```

When overrides are detected, the compiler emits informational messages showing which transitions are being overridden.

**Orphan Override Detection:**

If a transition is marked with `@@ Aspect.overriding` but doesn't actually override anything, the compiler emits a warning when `Machine(assembly)` is called:

```scala mdoc:compile-only
// Use inline def to preserve assembly for orphan override detection
inline def orphanAssembly = assembly[S, E](
  (A via E1 to B) @@ Aspect.overriding,  // No duplicate to override!
)
val machine = Machine(orphanAssembly)
// Compiler will warn: S.A via E.E1: marked @@ Aspect.overriding but no duplicate to override
```

This helps catch refactoring issues where an override becomes orphaned after the original transition is removed.

---

[<< Previous: Core Concepts](core-concepts.md) | [Back to Index](DOCUMENTATION.md) | [Next: Running FSMs >>](running-fsms.md)
