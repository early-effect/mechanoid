package mechanoid.machine

import zio.*
import mechanoid.core.*
import mechanoid.runtime.FSMRuntime
import mechanoid.visualization.{TransitionMeta, TransitionKind}

/** A finite state machine definition using the suite-style DSL.
  *
  * Machine holds all runtime data for an FSM:
  *   - Transitions map (state hash, event hash) → action
  *   - State timeouts
  *   - Per-transition effects (entry effects and producing effects)
  *   - Visualization metadata
  *
  * @tparam S
  *   The state type (sealed enum or sealed trait - Finite typeclass derived automatically)
  * @tparam E
  *   The event type (sealed enum or sealed trait - Finite typeclass derived automatically)
  */
final class Machine[S, E] private[machine] (
    // Runtime data - events are just E now (no Timed wrapper)
    private[mechanoid] val transitions: Map[(Int, Int), Transition[S, E, S]],
    private[mechanoid] val timeouts: Map[Int, Duration],
    private[mechanoid] val timeoutEvents: Map[Int, E], // state hash -> event to fire on timeout
    private[mechanoid] val transitionMeta: List[TransitionMeta],
    // Per-transition effects: (event, targetState) => effect
    private[mechanoid] val entryEffects: Map[(Int, Int), EntryEffect[E, S]],
    private[mechanoid] val producingEffects: Map[(Int, Int), ProducingEffect[E, S, E]],
    // Per-state effects: state hash -> effect (from Assembly.onEnter/onExit)
    private[mechanoid] val stateEntryEffects: Map[Int, (E, S) => ZIO[Any, Any, Unit]],
    private[mechanoid] val stateExitEffects: Map[Int, (E, S) => ZIO[Any, Any, Unit]],
    // Spec data for compile-time validation and introspection
    private[machine] val specs: List[TransitionSpec[S, E, ?]],
)(using
    private[mechanoid] val stateEnum: Finite[S],
    private[mechanoid] val eventEnum: Finite[E], // Just E, no Timed wrapper
):

  /** Build and start the FSM runtime with the given initial state.
    *
    * Creates an in-memory FSM runtime suitable for testing or single-process use.
    */
  def start(initial: S): ZIO[Scope, MechanoidError, FSMRuntime[Unit, S, E]] =
    FSMRuntime.make(this, initial)

  /** Get state names for visualization (caseHash -> name). */
  def stateNames: Map[Int, String] = stateEnum.caseNames

  /** Get event names for visualization (caseHash -> name, includes Timeout). */
  def eventNames: Map[Int, String] = eventEnum.caseNames

  // ============================================
  // Internal mutation methods (for building)
  // ============================================

  /** Add a transition using event hash directly (for multi-event builders). */
  private[mechanoid] def addTransitionWithMetaByHash(
      fromCaseHash: Int,
      eventCaseHash: Int,
      transition: Transition[S, E, S],
      meta: TransitionMeta,
  ): Machine[S, E] =
    new Machine(
      transitions + ((fromCaseHash, eventCaseHash) -> transition),
      timeouts,
      timeoutEvents,
      transitionMeta :+ meta,
      entryEffects,
      producingEffects,
      stateEntryEffects,
      stateExitEffects,
      specs,
    )

  /** Set timeout for a state. */
  private[mechanoid] def setStateTimeout(
      stateCaseHash: Int,
      timeout: Duration,
  ): Machine[S, E] =
    new Machine(
      transitions,
      timeouts + (stateCaseHash -> timeout),
      timeoutEvents,
      transitionMeta,
      entryEffects,
      producingEffects,
      stateEntryEffects,
      stateExitEffects,
      specs,
    )
end Machine

object Machine:

  /** Create a Machine from a validated Assembly.
    *
    * This is the ONLY public way to create a Machine. The assembly must be created via the `assembly` or `assemblyAll`
    * macros, which perform compile-time validation of transitions.
    *
    * @tparam S
    *   The state type (must have Finite instance)
    * @tparam E
    *   The event type (must have Finite instance)
    * @param assembly
    *   A validated assembly created via `assembly()` or `assemblyAll()`
    * @return
    *   A runnable Machine
    *
    * @example
    *   {{{
    * import mechanoid.Mechanoid.*
    *
    * // Using assembly()
    * val machine = Machine(assembly[State, Event](
    *   Idle via Start to Running,
    *   Running via Stop to Idle,
    * ))
    *
    * // Using assemblyAll with block syntax
    * val machine2 = Machine(assemblyAll[State, Event]:
    *   Idle via Start to Running
    *   Running via Stop to Idle
    * )
    *   }}}
    */
  inline def apply[S, E](inline assembly: Assembly[S, E])(using
      inline finiteS: Finite[S],
      inline finiteE: Finite[E],
  ): Machine[S, E] =
    ${ MachineMacros.applyImpl[S, E]('assembly, 'finiteS, 'finiteE) }

  /** Create a Machine from validated specs.
    *
    * This is an internal method used by `MachineMacros.applyImpl`. All specs reaching this method have already passed
    * compile-time duplicate detection in the `assembly`/`assemblyAll`/`combine` macros.
    */
  private[machine] def fromSpecs[S: Finite, E: Finite](
      specs: List[TransitionSpec[S, E, ?]],
      stateEntryEffects: Map[Int, (E, S) => ZIO[Any, Any, Unit]] = Map.empty,
      stateExitEffects: Map[Int, (E, S) => ZIO[Any, Any, Unit]] = Map.empty,
  ): Machine[S, E] =
    var transitions         = Map.empty[(Int, Int), Transition[S, E, S]]
    var transitionMetaList  = List.empty[TransitionMeta]
    var stateTimeouts       = Map.empty[Int, Duration]
    var stateTimeoutEvents  = Map.empty[Int, E]
    var entryEffectsMap     = Map.empty[(Int, Int), EntryEffect[E, S]]
    var producingEffectsMap = Map.empty[(Int, Int), ProducingEffect[E, S, E]]

    val stateEnumInstance = summon[Finite[S]]

    for spec <- specs do
      val transition = spec.handler match
        case Handler.Goto(target) =>
          val targetState = target.asInstanceOf[S]
          Transition[S, E, S](
            (_, _) => ZIO.succeed(TransitionResult.Goto(targetState)),
            None,
          )
        case Handler.Stay =>
          Transition[S, E, S](
            (_, _) => ZIO.succeed(TransitionResult.Stay),
            None,
          )
        case Handler.Stop(reason) =>
          Transition[S, E, S](
            (_, _) => ZIO.succeed(TransitionResult.Stop(reason)),
            None,
          )

      val targetHash = spec.handler match
        case Handler.Goto(target) =>
          Some(stateEnumInstance.caseHash(target.asInstanceOf[S]))
        case _ => None

      val kind = spec.handler match
        case Handler.Goto(_)      => TransitionKind.Goto
        case Handler.Stay         => TransitionKind.Stay
        case Handler.Stop(reason) => TransitionKind.Stop(reason)

      for
        stateHash <- spec.stateHashes
        eventHash <- spec.eventHashes
      do
        val key  = (stateHash, eventHash)
        val meta = TransitionMeta(stateHash, eventHash, targetHash, kind)
        transitions = transitions + (key -> transition)
        transitionMetaList = transitionMetaList :+ meta

        spec.entryEffect.foreach { f =>
          entryEffectsMap = entryEffectsMap + (key -> f)
        }
        spec.producingEffect.foreach { f =>
          producingEffectsMap = producingEffectsMap + (key -> f)
        }
      end for

      (spec.targetTimeout, spec.handler) match
        case (Some(duration), Handler.Goto(target)) =>
          val targetStateHash = stateEnumInstance.caseHash(target.asInstanceOf[S])
          stateTimeouts = stateTimeouts + (targetStateHash -> duration)
          spec.targetTimeoutConfig.foreach { config =>
            stateTimeoutEvents = stateTimeoutEvents + (targetStateHash -> config.event.asInstanceOf[E])
          }
        case _ =>
      end match
    end for

    new Machine(
      transitions,
      stateTimeouts,
      stateTimeoutEvents,
      transitionMetaList,
      entryEffectsMap,
      producingEffectsMap,
      stateEntryEffects,
      stateExitEffects,
      specs,
    )
  end fromSpecs

  /** Create an empty Machine. */
  def empty[S: Finite, E: Finite]: Machine[S, E] =
    new Machine(Map.empty, Map.empty, Map.empty, Nil, Map.empty, Map.empty, Map.empty, Map.empty, Nil)
end Machine
