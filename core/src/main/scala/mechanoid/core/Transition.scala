package mechanoid.core

import zio.*

/** A transition definition from one state to another via an event.
  *
  * All transition actions return `MechanoidError` as the error type. User errors are wrapped in `ActionFailedError`.
  *
  * @tparam S
  *   The base state type
  * @tparam E
  *   The event type that triggers this transition
  * @tparam S2
  *   The target state type
  * @param action
  *   A function that receives the current state and event, returning the transition result
  * @param description
  *   Optional human-readable description of this transition
  */
final case class Transition[-S, -E, +S2](
    action: (S, E) => ZIO[Any, MechanoidError, TransitionResult[S2]],
    description: Option[String] = None,
)

object Transition:
  /** Create a simple transition that goes to a target state. */
  def goto[S, E, S2](target: S2): Transition[S, E, S2] =
    Transition((_, _) => ZIO.succeed(TransitionResult.Goto(target)), None)

  /** Create a transition that stays in the current state. */
  def stay[S, E]: Transition[S, E, S] =
    Transition((_, _) => ZIO.succeed(TransitionResult.Stay), None)

/** Timeout configuration for a state.
  *
  * When the FSM enters a state with a timeout, if no event is received within the duration, the timeout action is
  * automatically executed.
  *
  * @tparam S
  *   The state type this timeout applies to
  * @tparam S2
  *   The potential target state type
  */
final case class StateTimeout[-S, +S2](
    duration: Duration,
    action: ZIO[Any, MechanoidError, TransitionResult[S2]],
)

/** Result of a transition.
  *
  * This is returned by `FSMRuntime.send` and includes the transition result (Stay, Goto, Stop).
  *
  * Note: Per-transition effects (`.onEntry` and `.producing`) are executed automatically by the runtime and do not
  * appear in this outcome. Entry effects run synchronously before `send` returns. Producing effects run asynchronously
  * and send their produced events back to the FSM.
  *
  * @tparam S
  *   The state type
  */
final case class TransitionOutcome[+S](
    result: TransitionResult[S]
)

object TransitionOutcome:
  /** Create an outcome from a result. */
  def apply[S](result: TransitionResult[S]): TransitionOutcome[S] =
    new TransitionOutcome(result)
