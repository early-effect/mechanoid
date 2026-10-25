package mechanoid.visualization

import zio.Duration
import java.time.Instant

/** The kind of transition for visualization purposes. */
enum TransitionKind:
  case Goto                         // Transition to a specific state
  case Stay                         // Remain in current state
  case Stop(reason: Option[String]) // Terminal transition

/** Metadata about a transition for visualization purposes. */
case class TransitionMeta(
    fromStateCaseHash: Int,
    eventCaseHash: Int,
    targetStateCaseHash: Option[Int], // None for Stay/Stop, Some for Goto
    kind: TransitionKind,
)

/** Metadata about a state for visualization. */
case class StateMeta(
    caseHash: Int,
    name: String,
    hasEntryAction: Boolean,
    hasExitAction: Boolean,
    timeout: Option[Duration],
)

/** Complete FSM metadata for static structure visualization. */
case class FSMMeta(
    transitions: List[TransitionMeta],
    states: List[StateMeta],
    stateNames: Map[Int, String], // caseHash -> name
    eventNames: Map[Int, String], // caseHash -> name
)

/** A single step in an FSM execution trace. */
case class TraceStep[S, E](
    sequenceNumber: Int,
    from: S,
    to: S,
    event: E,
    timestamp: Instant,
    isTimeout: Boolean,
):
  def isSelfTransition: Boolean = from == to

/** Complete execution trace for runtime visualization. */
case class ExecutionTrace[S, E](
    instanceId: String,
    initialState: S,
    currentState: S,
    steps: List[TraceStep[S, E]],
):
  def isEmpty: Boolean        = steps.isEmpty
  def stepCount: Int          = steps.size
  def isTerminal: Boolean     = steps.lastOption.exists(s => s.to == s.from && s.isTimeout)
  def visitedStates: Set[S]   = steps.flatMap(s => Set(s.from, s.to)).toSet + initialState
  def triggeredEvents: Set[E] = steps.map(_.event).toSet

object ExecutionTrace:
  /** Create an empty trace starting from an initial state. */
  def empty[S, E](
      instanceId: String,
      initialState: S,
  ): ExecutionTrace[S, E] =
    ExecutionTrace(instanceId, initialState, initialState, Nil)
