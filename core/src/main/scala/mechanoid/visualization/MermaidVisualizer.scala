package mechanoid.visualization

import mechanoid.core.*
import mechanoid.machine.Machine
import zio.Duration

/** Generates Mermaid diagram syntax for FSM visualization. */
object MermaidVisualizer:

  /** Generate a state diagram showing FSM structure.
    *
    * Output format:
    * ```mermaid
    * stateDiagram-v2
    *     [*] --> Created
    *     Created --> Processing: StartEvent
    *     Processing --> Completed: FinishEvent
    * ```
    */
  def stateDiagram[S, E](
      fsm: Machine[S, E],
      initialState: Option[S] = None,
  ): String =
    val sb = StringBuilder()
    sb.append("stateDiagram-v2\n")

    // Add initial state arrow if provided
    initialState.foreach { init =>
      sb.append(s"    [*] --> ${fsm.stateEnum.nameOf(init)}\n")
    }

    // Group transitions by source state for cleaner output
    val transitionsBySource = fsm.transitionMeta.groupBy(_.fromStateCaseHash)

    // Add transitions
    transitionsBySource.toList.sortBy(_._1).foreach { case (fromCaseHash, transitions) =>
      val fromName = fsm.stateEnum.nameFor(fromCaseHash)
      transitions.foreach { meta =>
        val eventName = fsm.eventEnum.nameFor(meta.eventCaseHash)

        meta.kind match
          case TransitionKind.Goto =>
            val targetName = fsm.stateEnum.nameFor(meta.targetStateCaseHash.get)
            sb.append(s"    $fromName --> $targetName: $eventName\n")
          case TransitionKind.Stay =>
            sb.append(s"    $fromName --> $fromName: $eventName\n")
          case TransitionKind.Stop(reason) =>
            val label = reason.fold(s"$eventName [stop]")(r => s"$eventName [stop: $r]")
            sb.append(s"    $fromName --> [*]: $label\n")
      }
    }

    // Add state notes for timeouts
    fsm.timeouts.foreach { case (stateCaseHash, duration) =>
      val stateName = fsm.stateEnum.nameFor(stateCaseHash)
      sb.append(s"    note right of $stateName\n")
      sb.append(s"      timeout: ${formatDuration(duration)}\n")
      sb.append(s"    end note\n")
    }

    sb.toString
  end stateDiagram

  /** Generate a sequence diagram showing execution trace.
    *
    * Output format:
    * ```mermaid
    * sequenceDiagram
    *     participant FSM
    *     Note over FSM: Created
    *     FSM->>FSM: PaymentSucceeded(TXN-123)
    *     Note over FSM: Processing
    *     FSM->>FSM: FinishEvent
    *     Note over FSM: Completed
    * ```
    *
    * Note: For parameterized events, the full representation with actual runtime values is shown (e.g.,
    * `PaymentSucceeded(TXN-123)` instead of just `PaymentSucceeded`).
    */
  def sequenceDiagram[S, E](
      trace: ExecutionTrace[S, E],
      stateEnum: Finite[S],
      @scala.annotation.nowarn("msg=unused") eventEnum: Finite[E],
  ): String =
    val sb = StringBuilder()
    sb.append("sequenceDiagram\n")
    sb.append(s"    participant FSM as ${trace.instanceId}\n")

    // Initial state
    sb.append(s"    Note over FSM: ${stateEnum.nameOf(trace.initialState)}\n")

    // Each transition step
    trace.steps.foreach { step =>
      // For runtime traces, show the full event representation with actual values
      val eventLabel =
        if step.isTimeout then "Timeout"
        else formatEventForDiagram(step.event)

      val toName = stateEnum.nameOf(step.to)

      if step.isSelfTransition then sb.append(s"    FSM->>FSM: $eventLabel (stay)\n")
      else
        sb.append(s"    FSM->>FSM: $eventLabel\n")
        sb.append(s"    Note over FSM: $toName\n")
    }

    // Current state marker
    sb.append(s"    Note over FSM: Current: ${stateEnum.nameOf(trace.currentState)}\n")

    sb.toString
  end sequenceDiagram

  /** Format an event for diagram display.
    *
    * For case objects: returns just the name (e.g., "Cancel") For parameterized case classes: returns name with params
    * (e.g., "PaymentSucceeded(TXN-123)")
    *
    * Long parameter values are truncated for readability.
    */
  def formatEventForDiagram[E](event: E): String =
    formatValueForDiagram(event)

  /** Format a state for diagram display.
    *
    * For case objects: returns just the name (e.g., "Idle") For parameterized case classes: returns name with params
    * (e.g., "Connecting(3)")
    *
    * Long parameter values are truncated for readability.
    */
  def formatStateForDiagram[S](state: S): String =
    formatValueForDiagram(state)

  /** Format any value for diagram display, handling both case objects and parameterized case classes. */
  private def formatValueForDiagram[T](value: T): String =
    val full = value.toString
    // Truncate long parameter values for readability
    if full.length > 60 then
      val parenIdx = full.indexOf('(')
      if parenIdx > 0 then s"${full.substring(0, parenIdx)}(...)"
      else full.take(57) + "..."
    else escapeMermaid(full)

  /** Generate a flowchart showing execution path with highlighting.
    *
    * Output format:
    * ```mermaid
    * flowchart LR
    *     Created((Created))
    *     Processing((Processing))
    *     Completed((Completed))
    *     Created -->|StartEvent| Processing
    *     Processing -->|FinishEvent| Completed
    *     style Completed fill:#90EE90
    * ```
    */
  def flowchart[S, E](
      fsm: Machine[S, E],
      trace: Option[ExecutionTrace[S, E]] = None,
  ): String =
    val sb = StringBuilder()
    sb.append("flowchart LR\n")

    // Collect all states
    val allStates = fsm.transitionMeta
      .flatMap { meta =>
        List(meta.fromStateCaseHash) ++ meta.targetStateCaseHash.toList
      }
      .distinct
      .sorted

    // Define state nodes
    allStates.foreach { stateCaseHash =>
      val stateName = fsm.stateEnum.nameFor(stateCaseHash)
      sb.append(s"    $stateName(($stateName))\n")
    }

    // Add terminal node if any stop transitions
    val hasStopTransitions = fsm.transitionMeta.exists(_.kind.isInstanceOf[TransitionKind.Stop])
    if hasStopTransitions then sb.append("    END([End])\n")

    sb.append("\n")

    // Add transitions
    fsm.transitionMeta.foreach { meta =>
      val fromName  = fsm.stateEnum.nameFor(meta.fromStateCaseHash)
      val eventName = fsm.eventEnum.nameFor(meta.eventCaseHash)

      meta.kind match
        case TransitionKind.Goto =>
          val targetName = fsm.stateEnum.nameFor(meta.targetStateCaseHash.get)
          sb.append(s"    $fromName -->|$eventName| $targetName\n")
        case TransitionKind.Stay =>
          sb.append(s"    $fromName -->|$eventName| $fromName\n")
        case TransitionKind.Stop(_) =>
          sb.append(s"    $fromName -->|$eventName| END\n")
    }

    // Highlight current state and visited path if trace provided
    trace.foreach { t =>
      sb.append("\n")
      // Highlight visited states
      t.visitedStates.foreach { state =>
        val stateName = fsm.stateEnum.nameOf(state)
        sb.append(s"    style $stateName fill:#ADD8E6\n") // Light blue for visited
      }
      // Highlight current state
      val currentName = fsm.stateEnum.nameOf(t.currentState)
      sb.append(s"    style $currentName fill:#90EE90\n") // Light green for current
    }

    sb.toString
  end flowchart

  private def formatDuration(d: Duration): String =
    if d.toMillis < 1000 then s"${d.toMillis}ms"
    else if d.toSeconds < 60 then s"${d.toSeconds}s"
    else if d.toMinutes < 60 then s"${d.toMinutes}m"
    else s"${d.toHours}h"

  /** Escape special characters for Mermaid diagram text. */
  private def escapeMermaid(text: String): String =
    text
      .replace("#", "#35;")
      .replace(";", "#59;")
      .replace("<", "#60;")
      .replace(">", "#62;")

  /** Simplify a fully qualified function name to just the method name for cleaner display. */
  private def simplifyFunctionName(fqn: String): String =
    // Extract just the method name from fully qualified names like
    // "mechanoid.examples.PetStoreApp$.OrderFSMManager.someFunction"
    fqn.split('.').lastOption.getOrElse(fqn)

end MermaidVisualizer
