package mechanoid.machine

import mechanoid.core.*

// ============================================
// Terminal objects for non-goto transitions
// ============================================

/** Terminal object for stay transitions.
  *
  * Usage: `A via E1 to stay`
  */
object stay

/** Terminal object for stop transitions.
  *
  * Usage: `A via E1 to stop` or `A via E1 to stop("reason")`
  */
object stop:
  def apply(reason: String): StopReason = StopReason(reason)

/** Wrapper for stop with a reason. */
final case class StopReason(reason: String)

// ============================================
// Matcher types for hierarchical matching
// ============================================

/** Marker trait for matcher types. Used with NotGiven to exclude matchers from the plain state extension.
  */
trait IsMatcher

/** Matcher for all children of a sealed parent type.
  *
  * Created by `all[Parent]` macro. Contains pre-computed hashes of all leaf children.
  *
  * Usage: `all[ParentState] via Event to Target`
  */
final class AllMatcher[T](
    val hashes: Set[Int],
    val names: List[String],
) extends IsMatcher:
  /** Start building a transition from all matched states. */
  inline infix def via[E](inline event: E): ViaBuilder[T, E] =
    val eventHash = Macros.computeHashFor(event)
    val eventName = event.toString
    new ViaBuilder[T, E](hashes, Set(eventHash), names, List(eventName))

  /** Handle event matcher for parameterized case classes. */
  infix def via[E](eventMatcher: EventMatcher[E]): ViaBuilder[T, E] =
    new ViaBuilder[T, E](hashes, Set(eventMatcher.hash), names, List(eventMatcher.name))

  /** Handle anyOf events. */
  infix def viaAnyOf[E](events: AnyOfEventMatcher[E]): ViaBuilder[T, E] =
    new ViaBuilder[T, E](hashes, events.hashes, names, events.names)

  /** Handle all events. */
  infix def viaAll[E](events: AllMatcher[E]): ViaBuilder[T, E] =
    new ViaBuilder[T, E](hashes, events.hashes, names, events.names)
end AllMatcher

/** Matcher for specific state values.
  *
  * Created by `anyOf(StateA, StateB, ...)`. Contains the actual values and their computed hashes.
  *
  * Usage: `anyOf(ChildA, ChildB) via Event to Target`
  */
final class AnyOfMatcher[S](
    val values: Seq[S],
    val hashes: Set[Int],
    val names: List[String],
) extends IsMatcher:
  /** Start building a transition from specific states. */
  inline infix def via[E](inline event: E): ViaBuilder[S, E] =
    val eventHash = Macros.computeHashFor(event)
    val eventName = event.toString
    new ViaBuilder[S, E](hashes, Set(eventHash), names, List(eventName))

  /** Handle event matcher for parameterized case classes. */
  infix def via[E](eventMatcher: EventMatcher[E]): ViaBuilder[S, E] =
    new ViaBuilder[S, E](hashes, Set(eventMatcher.hash), names, List(eventMatcher.name))

  /** Handle anyOf events. */
  infix def viaAnyOf[E](events: AnyOfEventMatcher[E]): ViaBuilder[S, E] =
    new ViaBuilder[S, E](hashes, events.hashes, names, events.names)
end AnyOfMatcher

/** Matcher for specific event values.
  *
  * Created by `anyOf(EventA, EventB, ...)`. Contains the actual values and their computed hashes.
  *
  * Usage: `State via anyOf(Click, Tap) to Target`
  */
final class AnyOfEventMatcher[E](
    val values: Seq[E],
    val hashes: Set[Int],
    val names: List[String],
)

// ============================================
// ViaBuilder - the intermediate builder
// ============================================

/** Builder after `State via Event`, ready for the `to` method.
  *
  * Contains pre-computed state and event hashes for efficient duplicate detection.
  */
final class ViaBuilder[S, E](
    val stateHashes: Set[Int],
    val eventHashes: Set[Int],
    val stateNames: List[String],
    val eventNames: List[String],
):
  /** Transition to a target state. */
  infix def to[S2](target: S2)(using
      scala.util.NotGiven[S2 <:< TimedTarget[?, ?]]
  ): TransitionSpec[S, E, S2] =
    TransitionSpec.goto[S, E, S2](stateHashes, eventHashes, stateNames, eventNames, target)

  /** Transition to a timed target state (starts timeout timer on entry).
    *
    * Usage:
    * {{{
    * val timedWaiting = Waiting @@ timeout(30.seconds, TimeoutEvent)
    * Idle via Start to timedWaiting
    * }}}
    */
  infix def to[S2, TE](target: TimedTarget[S2, TE])(using Finite[TE]): TransitionSpec[S, E, S2] =
    TransitionSpec.gotoTimed[S, E, S2, TE](stateHashes, eventHashes, stateNames, eventNames, target)

  /** Alias for `to` using >> operator. */
  def >>[S2](target: S2)(using scala.util.NotGiven[S2 <:< TimedTarget[?, ?]]): TransitionSpec[S, E, S2] =
    to(target)

  /** Stay in the current state. */
  infix def to(terminal: stay.type): TransitionSpec[S, E, Nothing] =
    TransitionSpec.stay[S, E](stateHashes, eventHashes, stateNames, eventNames)

  /** Stop the FSM. */
  infix def to(terminal: stop.type): TransitionSpec[S, E, Nothing] =
    TransitionSpec.stop[S, E](stateHashes, eventHashes, stateNames, eventNames)

  /** Stop the FSM with a reason. */
  infix def to(terminal: StopReason): TransitionSpec[S, E, Nothing] =
    TransitionSpec.stop[S, E](stateHashes, eventHashes, stateNames, eventNames, Some(terminal.reason))
end ViaBuilder
