package mechanoid.machine

import mechanoid.visualization.TransitionKind

/** Query the assembly graph of a [[Machine]]: the same edges Mermaid walks.
  *
  * These are declared transitions, not reducer outcomes. A Launch edge can exist on an incomplete Draft; `send` then
  * fails with [[mechanoid.core.ActionFailedError]]. Missing edges fail with [[mechanoid.core.InvalidTransitionError]].
  */
object MachineGraph:

  /** True when the assembly declares a transition from `from` on `event`. */
  def hasEdge[S, E](machine: Machine[S, E], from: S, event: E): Boolean =
    val fromHash  = machine.stateEnum.caseHash(from)
    val eventHash = machine.eventEnum.caseHash(event)
    machine.transitions.contains((fromHash, eventHash))

  /** Goto target leaf name, or the current leaf for Stay / Stop. `None` when there is no edge. */
  def destLeaf[S, E](machine: Machine[S, E], from: S, event: E): Option[String] =
    val fromHash  = machine.stateEnum.caseHash(from)
    val eventHash = machine.eventEnum.caseHash(event)
    machine.transitionMeta
      .findLast(meta => meta.fromStateCaseHash == fromHash && meta.eventCaseHash == eventHash)
      .map { meta =>
        meta.kind match
          case TransitionKind.Goto =>
            machine.stateEnum.nameFor(meta.targetStateCaseHash.getOrElse(fromHash))
          case TransitionKind.Stay | TransitionKind.Stop(_) =>
            machine.stateEnum.nameOf(from)
      }
  end destLeaf
end MachineGraph
