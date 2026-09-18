package mechanoid.machine

import zio.{Chunk, ZIO}
import scala.annotation.unchecked.uncheckedVariance

/** Type-safe wrapper for entry effects.
  *
  * Encapsulates `(E, S) => ZIO[Any, Any, Unit]` with proper variance handling.
  */
opaque type EntryEffect[-E, -S] = (E, S) => ZIO[Any, Any, Unit]

object EntryEffect:
  def apply[E, S](f: (E, S) => ZIO[Any, Any, Unit]): EntryEffect[E, S] = f

  extension [E, S](effect: EntryEffect[E, S]) def run(event: E, state: S): ZIO[Any, Any, Unit] = effect(event, state)

/** Type-safe wrapper for producing effects.
  *
  * Encapsulates `(E, S) => ZIO[Any, Any, R]` with proper variance handling.
  */
opaque type ProducingEffect[-E, -S, +R] = (E, S) => ZIO[Any, Any, R]

object ProducingEffect:
  def apply[E, S, R](f: (E, S) => ZIO[Any, Any, R]): ProducingEffect[E, S, R] = f

  extension [E, S, R](effect: ProducingEffect[E, S, R])
    def run(event: E, state: S): ZIO[Any, Any, R] = effect(event, state)

/** Reducer that builds the next state instance from `(current, event)`. */
opaque type PayloadReducer[-S, -E, +S2] = (S, E) => ZIO[Any, Any, S2]

object PayloadReducer:
  def pure[S, E, S2](f: (S, E) => S2): PayloadReducer[S, E, S2] =
    (s, e) => ZIO.succeed(f(s, e))

  def effect[S, E, S2](f: (S, E) => ZIO[Any, Any, S2]): PayloadReducer[S, E, S2] = f

  extension [S, E, S2](reducer: PayloadReducer[S, E, S2]) def run(s: S, e: E): ZIO[Any, Any, S2] = reducer(s, e)

/** Handler for what happens when a transition fires.
  *
  * The type parameter represents the target state type for Goto transitions.
  */
sealed trait Handler[+Target]

object Handler:
  /** Transition to a specific target instance (constant `to`). */
  case class Goto[+S](target: S) extends Handler[S]

  /** Goto whose instance is computed at send/replay. The leaf is fixed at assembly. */
  case class ComputeGoto[+S](leafHash: Int, leafName: String) extends Handler[S]

  /** Stay in the current leaf. Optional payload reducer rewrites the instance. */
  case object Stay extends Handler[Nothing]

  /** Stop the FSM. */
  case class Stop(reason: Option[String]) extends Handler[Nothing]
end Handler

/** A single transition specification for the suite DSL.
  *
  * Captures state/event hash information for compile-time duplicate detection, along with the handler that determines
  * what happens when the transition fires.
  *
  * @tparam SourceS
  *   The source state type (for matching in transition table)
  * @tparam E
  *   The event type (for matching)
  * @tparam TargetS
  *   The target state type (what we transition to). `Nothing` for stay/stop.
  */
final case class TransitionSpec[+SourceS, +E, +TargetS](
    stateHashes: Set[Int],     // Expanded from sealed hierarchies
    eventHashes: Set[Int],     // Expanded from sealed hierarchies
    stateNames: List[String],  // For error messages
    eventNames: List[String],  // For error messages
    targetDesc: String,        // "-> Paid", "stay", "stop" - for error messages
    isOverride: Boolean,       // If true, won't trigger duplicate error
    handler: Handler[TargetS], // Properly typed handler
    targetTimeouts: Chunk[NamedTimeout[TargetS @uncheckedVariance, ?]] = Chunk.empty,
    // Effects use @uncheckedVariance because they are contravariant in E/TargetS but TransitionSpec must be
    // covariant for hierarchical FSMs (e.g., TransitionSpec[InReview, _, _] <: TransitionSpec[DocumentState, _, _]).
    // This is safe because effects are stored (not passed through) and at runtime receive the actual types.
    entryEffect: Option[EntryEffect[E @uncheckedVariance, TargetS @uncheckedVariance]] = None,
    producingEffect: Option[ProducingEffect[E @uncheckedVariance, TargetS @uncheckedVariance, E @uncheckedVariance]] =
      None,
    // Compile-time validation: sealed ancestor hashes of the produced event type (E2).
    // Used by assembly macro to validate E2 shares a common ancestor (LUB) with FSM's event type E.
    producingAncestorHashes: Option[Set[Int]] = None,
    payload: Option[PayloadReducer[SourceS @uncheckedVariance, E @uncheckedVariance, TargetS @uncheckedVariance]] = None,
):
  /** Synchronous side effect on entry. Receives (event, targetState).
    *
    * Use this for side effects that should run when the transition occurs. The function receives the triggering event
    * and the new (target) state.
    *
    * @example
    *   {{{
    * val spec = (A via E1 to B).onEntry { (event, state) =>
    *   ZIO.logInfo(s"Transitioned to \$state")
    * }
    *   }}}
    *
    * @param f
    *   Function that runs side effect from (event, targetState)
    * @return
    *   A new TransitionSpec with the entry effect configured
    */
  infix def onEntry(f: (E, TargetS) => ZIO[Any, Any, Unit]): TransitionSpec[SourceS, E, TargetS] =
    copy(entryEffect = Some(EntryEffect(f)))

  /** Async effect that produces an event. Receives (event, targetState).
    *
    * Use this for async operations that should produce an event when complete. The function receives the triggering
    * event and the new (target) state, and returns a ZIO that produces an event. The produced event is automatically
    * sent to the FSM when the effect completes.
    *
    * '''Compile-time validation:''' The macro validates that E2 is a case of a sealed enum/trait, and the assembly
    * macro validates that E2 shares a common ancestor (LUB) with the FSM's event type. This ensures type safety at
    * compile time rather than runtime.
    *
    * @example
    *   {{{
    * val spec = (Created via StartPayment to Processing).producing { (event, state) =>
    *   paymentService.charge(event.amount).map {
    *     case Success(txnId) => PaymentSucceeded(txnId)
    *     case Failure(err)   => PaymentFailed(err)
    *   }
    * }
    *   }}}
    *
    * @param f
    *   Function that produces an event from (event, targetState)
    * @return
    *   A new TransitionSpec with the producing effect configured
    */
  inline infix def producing[E2](
      f: (E, TargetS) => ZIO[Any, Any, E2]
  ): TransitionSpec[SourceS, E, TargetS] =
    ${ ProducingMacros.producingImpl[SourceS, E, TargetS, E2]('{ this }, 'f) }

  /** Apply an aspect to this transition spec.
    *
    * @example
    *   {{{
    * val override_ = (A via E1 to B) @@ Aspect.overriding
    * val timed = (A via E1 to B) @@ Aspect.timeout(30.seconds, TimeoutEvent)
    *   }}}
    */
  infix def @@(aspect: Aspect): TransitionSpec[SourceS, E, TargetS] = aspect match
    case Aspect.overriding => copy(isOverride = true)

  /** Accumulate a named timeout on the target leaf. Stacking `@@` adds another timeout; it does not replace. */
  infix def @@[TE](timeout: NamedTimeout[TargetS, TE]): TransitionSpec[SourceS, E, TargetS] =
    copy(targetTimeouts = targetTimeouts :+ timeout)
end TransitionSpec

object TransitionSpec:
  /** Create a goto transition spec.
    *
    * @tparam SourceS
    *   Source state type (for matching)
    * @tparam SourceE
    *   Event type (for matching)
    * @tparam TargetS
    *   Target state type
    */
  def goto[SourceS, SourceE, TargetS](
      stateHashes: Set[Int],
      eventHashes: Set[Int],
      stateNames: List[String],
      eventNames: List[String],
      target: TargetS,
  ): TransitionSpec[SourceS, SourceE, TargetS] =
    TransitionSpec(
      stateHashes = stateHashes,
      eventHashes = eventHashes,
      stateNames = stateNames,
      eventNames = eventNames,
      targetDesc = s"-> ${target.toString}",
      isOverride = false,
      handler = Handler.Goto(target),
      entryEffect = None,
      producingEffect = None,
    )

  /** Create a goto transition spec to a timed target with user-defined timeout event.
    *
    * @tparam SourceS
    *   Source state type (for matching)
    * @tparam SourceE
    *   Event type (for matching)
    * @tparam TargetS
    *   Target state type
    * @tparam TE
    *   Timeout event type
    */
  def gotoTimed[SourceS, SourceE, TargetS, TE](
      stateHashes: Set[Int],
      eventHashes: Set[Int],
      stateNames: List[String],
      eventNames: List[String],
      target: TimedTarget[TargetS, TE],
  ): TransitionSpec[SourceS, SourceE, TargetS] =
    TransitionSpec(
      stateHashes = stateHashes,
      eventHashes = eventHashes,
      stateNames = stateNames,
      eventNames = eventNames,
      targetDesc = s"-> ${target.state.toString} @@ timeout(${target.duration}, ${target.timeoutEvent})",
      isOverride = false,
      handler = Handler.Goto(target.state),
      targetTimeouts = Chunk(
        NamedTimeout(target.timeoutEvent, None, TimeoutDeadline.After(target.duration))
      ),
      entryEffect = None,
      producingEffect = None,
    )

  /** Create a stay transition spec.
    *
    * @tparam SourceS
    *   Source state type (for matching)
    * @tparam SourceE
    *   Event type (for matching)
    */
  def stay[SourceS, SourceE](
      stateHashes: Set[Int],
      eventHashes: Set[Int],
      stateNames: List[String],
      eventNames: List[String],
  ): TransitionSpec[SourceS, SourceE, Nothing] =
    TransitionSpec(
      stateHashes = stateHashes,
      eventHashes = eventHashes,
      stateNames = stateNames,
      eventNames = eventNames,
      targetDesc = "stay",
      isOverride = false,
      handler = Handler.Stay,
      entryEffect = None,
      producingEffect = None,
    )

  /** Create a stop transition spec.
    *
    * @tparam SourceS
    *   Source state type (for matching)
    * @tparam SourceE
    *   Event type (for matching)
    */
  def stop[SourceS, SourceE](
      stateHashes: Set[Int],
      eventHashes: Set[Int],
      stateNames: List[String],
      eventNames: List[String],
      reason: Option[String] = None,
  ): TransitionSpec[SourceS, SourceE, Nothing] =
    TransitionSpec(
      stateHashes = stateHashes,
      eventHashes = eventHashes,
      stateNames = stateNames,
      eventNames = eventNames,
      targetDesc = reason.fold("stop")(r => s"stop($r)"),
      isOverride = false,
      handler = Handler.Stop(reason),
      entryEffect = None,
      producingEffect = None,
    )

  /** Goto whose instance is computed from `(current, event)`. `leafHash` is the declared Finite leaf. */
  def computeGoto[SourceS, SourceE, TargetS](
      stateHashes: Set[Int],
      eventHashes: Set[Int],
      stateNames: List[String],
      eventNames: List[String],
      leafHash: Int,
      leafName: String,
      reducer: PayloadReducer[SourceS, SourceE, TargetS],
  ): TransitionSpec[SourceS, SourceE, TargetS] =
    TransitionSpec(
      stateHashes = stateHashes,
      eventHashes = eventHashes,
      stateNames = stateNames,
      eventNames = eventNames,
      targetDesc = s"-> $leafName",
      isOverride = false,
      handler = Handler.ComputeGoto(leafHash, leafName),
      entryEffect = None,
      producingEffect = None,
      payload = Some(reducer),
    )

  /** Stay that rewrites the current instance. Lifecycle stays Stay (no timeout reset). */
  def computeStay[SourceS, SourceE](
      stateHashes: Set[Int],
      eventHashes: Set[Int],
      stateNames: List[String],
      eventNames: List[String],
      reducer: PayloadReducer[SourceS, SourceE, SourceS],
  ): TransitionSpec[SourceS, SourceE, SourceS] =
    TransitionSpec(
      stateHashes = stateHashes,
      eventHashes = eventHashes,
      stateNames = stateNames,
      eventNames = eventNames,
      targetDesc = "stay",
      isOverride = false,
      handler = Handler.Stay,
      entryEffect = None,
      producingEffect = None,
      payload = Some(reducer),
    )
end TransitionSpec
