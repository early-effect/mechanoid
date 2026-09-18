package mechanoid.machine

import zio.Duration

/** Aspect that can modify transition specs or state configurations.
  *
  * Inspired by zio-test's TestAspect, aspects allow modifying specs with the `@@` operator.
  */
sealed trait Aspect

object Aspect:
  /** Mark a transition as an intentional override.
    *
    * When duplicate transitions are detected, those marked with `@@ overriding` will not trigger a compile error.
    * Instead, they will override any previous definition for the same (state, event) pair. The last override wins.
    */
  case object overriding extends Aspect

  /** Duration sugar. The timeout name is generated from the event at assembly (`Finite.nameOf`). */
  def timeout[E](duration: Duration, event: E): NamedTimeout[Any, E] =
    NamedTimeout(event, None, TimeoutDeadline.After(duration))

  /** Pin the timeout event, then supply a Duration, Instant, or `S => Instant`. */
  def timeout[E](event: E): TimeoutBuilder[E] =
    TimeoutBuilder(event, None)

  /** Pin the timeout event and an explicit name, then supply the deadline. */
  def timeout[E](event: E, name: String): TimeoutBuilder[E] =
    TimeoutBuilder(event, Some(name))
end Aspect

/** Internal wrapper for timeout configuration on a target state.
  *
  * Prefer `@@ Aspect.timeout(event)(deadline)` on transitions instead.
  *
  * @tparam S
  *   The target state type
  * @tparam E
  *   The timeout event type
  * @param state
  *   The target state
  * @param duration
  *   How long before timeout fires
  * @param timeoutEvent
  *   The event to fire when the timeout expires
  */
final case class TimedTarget[S, E](state: S, duration: Duration, timeoutEvent: E)
