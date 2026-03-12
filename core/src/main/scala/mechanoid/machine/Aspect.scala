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

  /** Configure a timeout for a target state with a user-defined timeout event.
    *
    * Apply to a transition using `@@`. When the FSM enters the target state via such a transition, a timeout timer
    * starts. If no other transition occurs before the duration, the specified event is automatically fired.
    *
    * Usage:
    * {{{
    * import zio.Duration
    *
    * enum OrderEvent derives Finite:
    *   case Pay, PaymentTimeout
    *
    * val machine = Machine(assembly[State, OrderEvent](
    *   (Pending via Pay to Processing) @@ Aspect.timeout(30.seconds, PaymentTimeout),
    *   Processing via Complete to Done,
    *   Processing via PaymentTimeout to TimedOut, // Handle the timeout with user event
    * ))
    * }}}
    *
    * @param duration
    *   How long to wait before firing the timeout event
    * @param event
    *   The event to fire when the timeout expires
    */
  case class timeout[E](duration: Duration, event: E) extends Aspect
end Aspect

/** Internal wrapper for timeout configuration on a target state.
  *
  * This is used internally by the assembly macros when processing transitions to `TimedTarget` values. Prefer using `@@
  * Aspect.timeout(duration, event)` on transitions instead:
  *
  * {{{
  * (A via E1 to B) @@ Aspect.timeout(30.seconds, TimeoutEvent)
  * }}}
  *
  * @tparam S
  *   The state type
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

// Extension methods for @@ are defined in Macros.scala to keep all extensions together
