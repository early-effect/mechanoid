package mechanoid.machine

import mechanoid.core.*
import zio.{Duration, ZIO}
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

/** Handler for what happens when a transition fires.
  *
  * The type parameter represents the target state type for Goto transitions.
  */
sealed trait Handler[+Target]

object Handler:
  /** Transition to a specific target state. */
  case class Goto[+S](target: S) extends Handler[S]

  /** Stay in the current state. */
  case object Stay extends Handler[Nothing]

  /** Stop the FSM. */
  case class Stop(reason: Option[String]) extends Handler[Nothing]

/** Type-safe holder for timeout event configuration.
  *
  * This preserves the event type while allowing storage in contravariant TransitionSpec. The existential type `?`
  * allows safe storage while Machine can extract and properly type the events when building its lookup table.
  */
final case class TimeoutEventConfig[E](event: E, hash: Int)

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
    stateHashes: Set[Int],           // Expanded from sealed hierarchies
    eventHashes: Set[Int],           // Expanded from sealed hierarchies
    stateNames: List[String],        // For error messages
    eventNames: List[String],        // For error messages
    targetDesc: String,              // "-> Paid", "stay", "stop" - for error messages
    isOverride: Boolean,             // If true, won't trigger duplicate error
    handler: Handler[TargetS],       // Properly typed handler
    targetTimeout: Option[Duration], // If set, configure timeout on target state when entering
    targetTimeoutConfig: Option[TimeoutEventConfig[?]] = None, // Type-safe timeout event holder
    // Effects use @uncheckedVariance because they are contravariant in E/TargetS but TransitionSpec must be
    // covariant for hierarchical FSMs (e.g., TransitionSpec[InReview, _, _] <: TransitionSpec[DocumentState, _, _]).
    // This is safe because effects are stored (not passed through) and at runtime receive the actual types.
    entryEffect: Option[EntryEffect[E @uncheckedVariance, TargetS @uncheckedVariance]] = None,
    producingEffect: Option[ProducingEffect[E @uncheckedVariance, TargetS @uncheckedVariance, E @uncheckedVariance]] =
      None,
    // Compile-time validation: sealed ancestor hashes of the produced event type (E2).
    // Used by assembly macro to validate E2 shares a common ancestor (LUB) with FSM's event type E.
    producingAncestorHashes: Option[Set[Int]] = None,
):
  /** Synchronous side effect on entry. Receives (event, targetState).
    *
    * Use this for side effects that should run when the transition occurs. The function receives the triggering event
    * and the new (target) state.
    *
    * @example
    *   {{{
    * val spec = (A via E1 to B).onEntry { (event, state) =>
    *   ZIO.logInfo(s"Transitioned to $state")
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
  inline infix def producing[E2 <: E @uncheckedVariance](
      f: (E, TargetS) => ZIO[Any, Any, E2]
  ): TransitionSpec[SourceS, E, TargetS] =
    ${ ProducingMacros.producingImpl[SourceS, E, TargetS, E2]('this, 'f) }

  /** Configure timeout when entering target state. */
  def withTimeout(duration: Duration): TransitionSpec[SourceS, E, TargetS] =
    copy(targetTimeout = Some(duration))

  /** Apply an aspect to this transition spec.
    *
    * @example
    *   {{{
    * val override_ = (A via E1 to B) @@ Aspect.overriding
    * val timed = (A via E1 to B) @@ Aspect.timeout(30.seconds, TimeoutEvent)
    *   }}}
    */
  infix def @@(aspect: Aspect): TransitionSpec[SourceS, E, TargetS] = aspect match
    case Aspect.overriding               => copy(isOverride = true)
    case Aspect.timeout(duration, event) =>
      // Compute hash at runtime using the same logic as anyOf matchers
      val className = event.getClass.getName.stripSuffix("$").replace('$', '.')
      val hash      = event match
        case _: scala.reflect.Enum =>
          val caseName = event.toString
          s"$className.$caseName".hashCode
        case _ =>
          className.hashCode
      copy(
        targetTimeout = Some(duration),
        targetTimeoutConfig = Some(TimeoutEventConfig(event, hash)),
      )
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
      timeout: Option[Duration],
  ): TransitionSpec[SourceS, SourceE, TargetS] =
    TransitionSpec(
      stateHashes = stateHashes,
      eventHashes = eventHashes,
      stateNames = stateNames,
      eventNames = eventNames,
      targetDesc = s"-> ${target.toString}",
      isOverride = false,
      handler = Handler.Goto(target),
      targetTimeout = timeout,
      entryEffect = None,
      producingEffect = None,
    )

  /** Create a goto transition spec with no entry timeout. */
  def goto[SourceS, SourceE, TargetS](
      stateHashes: Set[Int],
      eventHashes: Set[Int],
      stateNames: List[String],
      eventNames: List[String],
      target: TargetS,
  ): TransitionSpec[SourceS, SourceE, TargetS] =
    goto(stateHashes, eventHashes, stateNames, eventNames, target, None)

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
  )(using se: Finite[TE]): TransitionSpec[SourceS, SourceE, TargetS] =
    TransitionSpec(
      stateHashes = stateHashes,
      eventHashes = eventHashes,
      stateNames = stateNames,
      eventNames = eventNames,
      targetDesc = s"-> ${target.state.toString} @@ timeout(${target.duration}, ${target.timeoutEvent})",
      isOverride = false,
      handler = Handler.Goto(target.state),
      targetTimeout = Some(target.duration),
      targetTimeoutConfig = Some(TimeoutEventConfig(target.timeoutEvent, se.caseHash(target.timeoutEvent))),
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
      targetTimeout = None,
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
      targetTimeout = None,
      entryEffect = None,
      producingEffect = None,
    )
end TransitionSpec
