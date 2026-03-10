package mechanoid.visualization

import mechanoid.core.*
import mechanoid.machine.Machine
import zio.Duration

/** Generates GraphViz DOT syntax for FSM visualization. */
object GraphVizVisualizer:

  /** Configuration for GraphViz output. */
  case class Config(
      rankDir: String = "LR",        // LR (left-right) or TB (top-bottom)
      nodeShape: String = "ellipse", // ellipse, box, circle, etc.
      fontSize: Int = 12,
      visitedColor: String = "#ADD8E6", // Light blue
      currentColor: String = "#90EE90", // Light green
      timeoutColor: String = "#FFB6C1", // Light pink for timeout states
  )

  object Config:
    val default: Config = Config()

  /** Generate a digraph showing FSM structure.
    *
    * Output format:
    * ```dot
    * digraph FSM {
    *     rankdir=LR;
    *     node [shape=ellipse];
    *     Created -> Processing [label="StartEvent"];
    *     Processing -> Completed [label="FinishEvent"];
    * }
    * ```
    */
  def digraph[S, E](
      fsm: Machine[S, E],
      name: String = "FSM",
      initialState: Option[S] = None,
      config: Config = Config.default,
  ): String =
    val sb = StringBuilder()
    sb.append(s"digraph $name {\n")
    sb.append(s"    rankdir=${config.rankDir};\n")
    sb.append(s"    fontsize=${config.fontSize};\n")
    sb.append(s"    node [shape=${config.nodeShape}, fontsize=${config.fontSize}];\n")
    sb.append(s"    edge [fontsize=${config.fontSize - 2}];\n")
    sb.append("\n")

    // Collect all states
    val allStates = fsm.transitionMeta
      .flatMap { meta =>
        List(meta.fromStateCaseHash) ++ meta.targetStateCaseHash.toList
      }
      .distinct
      .sorted

    // Define state nodes with labels showing timeout info
    allStates.foreach { stateCaseHash =>
      val stateName = fsm.stateEnum.nameFor(stateCaseHash)
      val timeout   = fsm.timeouts.get(stateCaseHash)

      val annotations = List(
        timeout.map(d => s"timeout: ${formatDuration(d)}")
      ).flatten

      val label =
        if annotations.isEmpty then stateName
        else s"$stateName\\n[${annotations.mkString(", ")}]"

      val style = timeout match
        case Some(_) => s", style=filled, fillcolor=\"${config.timeoutColor}\""
        case None    => ""

      sb.append(s"    $stateName [label=\"$label\"$style];\n")
    }

    // Add initial state marker
    initialState.foreach { init =>
      val initName = fsm.stateEnum.nameOf(init)
      sb.append(s"    __start__ [shape=point, width=0.2];\n")
      sb.append(s"    __start__ -> $initName;\n")
    }

    // Add terminal state marker if needed
    val hasStopTransitions = fsm.transitionMeta.exists(_.kind.isInstanceOf[TransitionKind.Stop])
    if hasStopTransitions then sb.append(s"    __end__ [shape=doublecircle, width=0.3, label=\"\"];\n")

    sb.append("\n")

    // Add transitions
    fsm.transitionMeta.foreach { meta =>
      val fromName  = fsm.stateEnum.nameFor(meta.fromStateCaseHash)
      val eventName = fsm.eventEnum.nameFor(meta.eventCaseHash)

      meta.kind match
        case TransitionKind.Goto =>
          val targetName = fsm.stateEnum.nameFor(meta.targetStateCaseHash.get)
          sb.append(s"    $fromName -> $targetName [label=\"$eventName\"];\n")
        case TransitionKind.Stay =>
          sb.append(s"    $fromName -> $fromName [label=\"$eventName\"];\n")
        case TransitionKind.Stop(_) =>
          sb.append(s"    $fromName -> __end__ [label=\"$eventName\"];\n")
    }

    sb.append("}\n")
    sb.toString
  end digraph

  /** Generate a digraph with execution trace highlighting.
    */
  def digraphWithTrace[S, E](
      fsm: Machine[S, E],
      trace: ExecutionTrace[S, E],
      name: String = "FSM",
      config: Config = Config.default,
  ): String =
    val sb = StringBuilder()
    sb.append(s"digraph $name {\n")
    sb.append(s"    rankdir=${config.rankDir};\n")
    sb.append(s"    fontsize=${config.fontSize};\n")
    sb.append(s"    node [shape=${config.nodeShape}, fontsize=${config.fontSize}];\n")
    sb.append(s"    edge [fontsize=${config.fontSize - 2}];\n")
    sb.append("\n")

    // Collect all states
    val allStates = fsm.transitionMeta
      .flatMap { meta =>
        List(meta.fromStateCaseHash) ++ meta.targetStateCaseHash.toList
      }
      .distinct
      .sorted

    // Track visited states and transitions
    val visitedStateCaseHashes = trace.visitedStates.map(fsm.stateEnum.caseHash)
    val currentStateCaseHash   = fsm.stateEnum.caseHash(trace.currentState)

    // Build set of taken transitions (from, to) by caseHash
    val takenTransitions = trace.steps.map { step =>
      val fromHash = fsm.stateEnum.caseHash(step.from)
      val toHash   = fsm.stateEnum.caseHash(step.to)
      (fromHash, toHash)
    }.toSet

    // Define state nodes with highlighting
    allStates.foreach { stateCaseHash =>
      val stateName = fsm.stateEnum.nameFor(stateCaseHash)
      val timeout   = fsm.timeouts.get(stateCaseHash)

      val annotations = List(
        timeout.map(d => s"timeout: ${formatDuration(d)}")
      ).flatten

      val label =
        if annotations.isEmpty then stateName
        else s"$stateName\\n[${annotations.mkString(", ")}]"

      val fillColor =
        if stateCaseHash == currentStateCaseHash then config.currentColor
        else if visitedStateCaseHashes.contains(stateCaseHash) then config.visitedColor
        else if timeout.isDefined then config.timeoutColor
        else "white"

      sb.append(s"    $stateName [label=\"$label\", style=filled, fillcolor=\"$fillColor\"];\n")
    }

    // Add initial state marker
    val initName = fsm.stateEnum.nameOf(trace.initialState)
    sb.append(s"    __start__ [shape=point, width=0.2];\n")
    sb.append(s"    __start__ -> $initName;\n")

    // Add terminal state marker if needed
    val hasStopTransitions = fsm.transitionMeta.exists(_.kind.isInstanceOf[TransitionKind.Stop])
    if hasStopTransitions then sb.append(s"    __end__ [shape=doublecircle, width=0.3, label=\"\"];\n")

    sb.append("\n")

    // Add transitions with highlighting for taken paths
    fsm.transitionMeta.foreach { meta =>
      val fromName  = fsm.stateEnum.nameFor(meta.fromStateCaseHash)
      val eventName = fsm.eventEnum.nameFor(meta.eventCaseHash)

      val targetOrd = meta.targetStateCaseHash.getOrElse(meta.fromStateCaseHash)
      val isTaken   = takenTransitions.contains((meta.fromStateCaseHash, targetOrd))
      val edgeStyle = if isTaken then ", penwidth=2, color=blue" else ""

      meta.kind match
        case TransitionKind.Goto =>
          val targetName = fsm.stateEnum.nameFor(meta.targetStateCaseHash.get)
          sb.append(s"    $fromName -> $targetName [label=\"$eventName\"$edgeStyle];\n")
        case TransitionKind.Stay =>
          sb.append(s"    $fromName -> $fromName [label=\"$eventName\"$edgeStyle];\n")
        case TransitionKind.Stop(_) =>
          sb.append(s"    $fromName -> __end__ [label=\"$eventName\"$edgeStyle];\n")
    }

    sb.append("}\n")
    sb.toString
  end digraphWithTrace

  /** Generate a timeline diagram showing execution history.
    *
    * This creates a horizontal timeline with states and transitions.
    */
  def timeline[S, E](
      trace: ExecutionTrace[S, E],
      stateEnum: Finite[S],
      @scala.annotation.nowarn("msg=unused") eventEnum: Finite[E],
      name: String = "Timeline",
  ): String =
    val sb = StringBuilder()
    sb.append(s"digraph $name {\n")
    sb.append("    rankdir=LR;\n")
    sb.append("    node [shape=box];\n")
    sb.append("\n")

    // Create nodes for each state in the timeline
    sb.append(s"    s0 [label=\"${stateEnum.nameOf(trace.initialState)}\", style=filled, fillcolor=\"#E0E0E0\"];\n")

    trace.steps.zipWithIndex.foreach { case (step, idx) =>
      val nodeId    = s"s${idx + 1}"
      val stateName = stateEnum.nameOf(step.to)
      val isLast    = idx == trace.steps.size - 1
      val fillColor = if isLast then "#90EE90" else "#ADD8E6"
      sb.append(s"    $nodeId [label=\"$stateName\", style=filled, fillcolor=\"$fillColor\"];\n")
    }

    sb.append("\n")

    // Create edges with event labels - show full event representation for parameterized types
    trace.steps.zipWithIndex.foreach { case (step, idx) =>
      val fromNode   = s"s$idx"
      val toNode     = s"s${idx + 1}"
      val eventLabel =
        if step.isTimeout then "Timeout"
        else formatEventLabel(step.event)
      sb.append(s"    $fromNode -> $toNode [label=\"$eventLabel\"];\n")
    }

    sb.append("}\n")
    sb.toString
  end timeline

  private def formatDuration(d: Duration): String =
    if d.toMillis < 1000 then s"${d.toMillis}ms"
    else if d.toSeconds < 60 then s"${d.toSeconds}s"
    else if d.toMinutes < 60 then s"${d.toMinutes}m"
    else s"${d.toHours}h"

  /** Format an event for DOT label display.
    *
    * Shows full representation for parameterized events, escaping special chars. Truncates very long values for
    * readability.
    */
  private def formatEventLabel[E](event: E): String =
    val full    = event.toString
    val escaped = full
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")

    // Truncate long parameter values for readability
    if escaped.length > 50 then
      val parenIdx = escaped.indexOf('(')
      if parenIdx > 0 then s"${escaped.substring(0, parenIdx)}(...)"
      else escaped.take(47) + "..."
    else escaped
  end formatEventLabel

end GraphVizVisualizer
