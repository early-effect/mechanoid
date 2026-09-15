package mechanoid.machine

import mechanoid.core.*
import scala.DummyImplicit
import zio.ZIO

// ============================================
// Terminal objects for non-goto transitions
// ============================================

/** Token for stay transitions. `to stay` is identity; `.to(stay) { (s, e) => ... }` rewrites payload. */
object stay

/** Contract class after `to[Leaf]`. The leaf is named by the type argument; `apply` builds the instance. */
final class ComputeTo[S, E, Leaf](
    stateHashes: Set[Int],
    eventHashes: Set[Int],
    stateNames: List[String],
    eventNames: List[String],
    leafHash: Int,
    leafName: String,
):
  def apply[A](f: (S, E) => A)(using DummyImplicit): TransitionSpec[S, E, S] =
    TransitionSpec.computeGoto(
      stateHashes,
      eventHashes,
      stateNames,
      eventNames,
      leafHash,
      leafName,
      PayloadReducer.pure((s, e) => f(s, e).asInstanceOf[S]),
    )

  def apply[A](f: (S, E) => ZIO[Any, Any, A]): TransitionSpec[S, E, S] =
    TransitionSpec.computeGoto(
      stateHashes,
      eventHashes,
      stateNames,
      eventNames,
      leafHash,
      leafName,
      PayloadReducer.effect((s, e) => f(s, e).map(_.asInstanceOf[S])),
    )
end ComputeTo

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
  /** Transition to a constant target instance. */
  infix def to[S2](target: S2)(using
      scala.util.NotGiven[S2 <:< TimedTarget[?, ?]]
  ): TransitionSpec[S, E, S2] =
    TransitionSpec.goto[S, E, S2](stateHashes, eventHashes, stateNames, eventNames, target)

  /** Name the target leaf, then `apply` the payload function. Usage: `.to[Live] { (s, e) => Live(...) }`. */
  inline def to[Leaf]: ComputeTo[S, E, Leaf] =
    Macros.requireLeaf[Leaf]
    ComputeTo(
      stateHashes,
      eventHashes,
      stateNames,
      eventNames,
      Macros.hashForType[Leaf],
      Macros.nameForType[Leaf],
    )
  end to

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

  infix def to[S2, TE](target: TimedTarget[S2, TE])(f: (S, E) => S2)(using
      Finite[TE],
      DummyImplicit,
  ): TransitionSpec[S, E, S2] =
    TransitionSpec
      .gotoTimed[S, E, S2, TE](stateHashes, eventHashes, stateNames, eventNames, target)
      .copy(payload = Some(PayloadReducer.pure(f)))

  infix def to[S2, TE](target: TimedTarget[S2, TE])(f: (S, E) => ZIO[Any, Any, S2])(using
      Finite[TE]
  ): TransitionSpec[S, E, S2] =
    TransitionSpec
      .gotoTimed[S, E, S2, TE](stateHashes, eventHashes, stateNames, eventNames, target)
      .copy(payload = Some(PayloadReducer.effect(f)))

  /** Alias for `to` using >> operator. */
  def >>[S2](target: S2)(using scala.util.NotGiven[S2 <:< TimedTarget[?, ?]]): TransitionSpec[S, E, S2] =
    to(target)

  /** Stay in the current leaf (identity). */
  infix def to(terminal: stay.type): TransitionSpec[S, E, Nothing] =
    TransitionSpec.stay[S, E](stateHashes, eventHashes, stateNames, eventNames)

  /** Rewrite payload and stay. `S` and `E` are already known, so the lambda needs no ascription. */
  infix def to[A](terminal: stay.type)(f: (S, E) => A)(using DummyImplicit): TransitionSpec[S, E, S] =
    TransitionSpec.computeStay(
      stateHashes,
      eventHashes,
      stateNames,
      eventNames,
      PayloadReducer.pure((s, e) => f(s, e).asInstanceOf[S]),
    )

  infix def to[A](terminal: stay.type)(f: (S, E) => ZIO[Any, Any, A]): TransitionSpec[S, E, S] =
    TransitionSpec.computeStay(
      stateHashes,
      eventHashes,
      stateNames,
      eventNames,
      PayloadReducer.effect((s, e) => f(s, e).map(_.asInstanceOf[S])),
    )

  /** Stop the FSM. */
  infix def to(terminal: stop.type): TransitionSpec[S, E, Nothing] =
    TransitionSpec.stop[S, E](stateHashes, eventHashes, stateNames, eventNames)

  /** Stop the FSM with a reason. */
  infix def to(terminal: StopReason): TransitionSpec[S, E, Nothing] =
    TransitionSpec.stop[S, E](stateHashes, eventHashes, stateNames, eventNames, Some(terminal.reason))
end ViaBuilder
