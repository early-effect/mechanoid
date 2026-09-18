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
    private[mechanoid] val timeouts: Map[Int, Chunk[TimeoutSpec[S, E]]],
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

  /** Named timeouts armed when entering this leaf. Empty if none. */
  def timeoutsFor(state: S): Chunk[TimeoutSpec[S, E]] =
    timeouts.getOrElse(stateEnum.caseHash(state), Chunk.empty)

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
    var stateTimeouts       = Map.empty[Int, Chunk[TimeoutSpec[S, E]]]
    var entryEffectsMap     = Map.empty[(Int, Int), EntryEffect[E, S]]
    var producingEffectsMap = Map.empty[(Int, Int), ProducingEffect[E, S, E]]

    val stateEnumInstance = summon[Finite[S]]
    val eventEnumInstance = summon[Finite[E]]

    def sameDeadline(a: TimeoutDeadline[S], b: TimeoutDeadline[S]): Boolean =
      (a, b) match
        case (TimeoutDeadline.After(d1), TimeoutDeadline.After(d2))           => d1 == d2
        case (TimeoutDeadline.At(i1), TimeoutDeadline.At(i2))                 => i1 == i2
        case (TimeoutDeadline.FromPayload(_), TimeoutDeadline.FromPayload(_)) => true
        case _                                                                => false

    def addTimeouts(leafHash: Int, decls: Chunk[NamedTimeout[?, ?]]): Unit =
      decls.foreach { decl =>
        val event    = decl.event.asInstanceOf[E]
        val name     = decl.name.getOrElse(eventEnumInstance.nameOf(event))
        val deadline = decl.deadline.asInstanceOf[TimeoutDeadline[S]]
        val spec     = TimeoutSpec(event, name, deadline)
        val existing = stateTimeouts.getOrElse(leafHash, Chunk.empty)
        existing.find(_.name == name) match
          case Some(prev)
              if eventEnumInstance.caseHash(prev.event) == eventEnumInstance.caseHash(event) &&
                sameDeadline(prev.deadline, deadline) =>
            ()
          case Some(_) =>
            throw IllegalArgumentException(
              s"Duplicate timeout name '$name' on leaf ${stateEnumInstance.nameFor(leafHash)}"
            )
          case None =>
            stateTimeouts = stateTimeouts.updated(leafHash, existing :+ spec)
        end match
      }

    def wrapReducer(reducer: PayloadReducer[S, E, S])(s: S, e: E): ZIO[Any, MechanoidError, S] =
      reducer
        .run(s, e)
        .foldZIO(
          {
            case afe: ActionFailedError[?] => ZIO.fail(afe)
            case other                     => ZIO.fail(ActionFailedError(other))
          },
          ZIO.succeed,
        )

    def enforceLeaf(expectedHash: Int, expectedName: String)(next: S): ZIO[Any, MechanoidError, S] =
      if stateEnumInstance.caseHash(next) == expectedHash then ZIO.succeed(next)
      else
        ZIO.fail(
          PayloadLeafMismatchError(
            declaredLeaf = expectedName,
            actualLeaf = stateEnumInstance.nameOf(next),
            actual = next,
          )
        )

    def typedPayload(spec: TransitionSpec[?, ?, ?]): Option[PayloadReducer[S, E, S]] =
      spec.payload.map(_.asInstanceOf[PayloadReducer[S, E, S]])

    def computedGoto(
        reducer: PayloadReducer[S, E, S],
        leafHash: Int,
        leafName: String,
    ): Transition[S, E, S] =
      Transition { (s, e) =>
        wrapReducer(reducer)(s, e).flatMap(enforceLeaf(leafHash, leafName)).map(TransitionResult.Goto(_))
      }

    def computedStay(reducer: PayloadReducer[S, E, S]): Transition[S, E, S] =
      Transition { (s, e) =>
        wrapReducer(reducer)(s, e)
          .flatMap(enforceLeaf(stateEnumInstance.caseHash(s), stateEnumInstance.nameOf(s)))
          .map(TransitionResult.Stay(_))
      }

    for spec <- specs do
      val transition = spec.handler match
        case Handler.Goto(target) =>
          val targetState = target.asInstanceOf[S]
          typedPayload(spec) match
            case None =>
              Transition[S, E, S](
                (_, _) => ZIO.succeed(TransitionResult.Goto(targetState)),
                None,
              )
            case Some(reducer) =>
              computedGoto(reducer, stateEnumInstance.caseHash(targetState), stateEnumInstance.nameOf(targetState))
        case Handler.ComputeGoto(leafHash, leafName) =>
          typedPayload(spec) match
            case None =>
              Transition[S, E, S](
                (_, _) => ZIO.fail(ActionFailedError("computed goto is missing its reducer")),
                None,
              )
            case Some(reducer) => computedGoto(reducer, leafHash, leafName)
        case Handler.Stay =>
          typedPayload(spec) match
            case None =>
              Transition[S, E, S](
                (s, _) => ZIO.succeed(TransitionResult.Stay(s)),
                None,
              )
            case Some(reducer) => computedStay(reducer)
        case Handler.Stop(reason) =>
          Transition[S, E, S](
            (_, _) => ZIO.succeed(TransitionResult.Stop(reason)),
            None,
          )

      val targetHash = spec.handler match
        case Handler.Goto(target)             => Some(stateEnumInstance.caseHash(target.asInstanceOf[S]))
        case Handler.ComputeGoto(leafHash, _) => Some(leafHash)
        case _                                => None

      val kind = spec.handler match
        case Handler.Goto(_) | Handler.ComputeGoto(_, _) => TransitionKind.Goto
        case Handler.Stay                                => TransitionKind.Stay
        case Handler.Stop(reason)                        => TransitionKind.Stop(reason)

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

      spec.handler match
        case Handler.Goto(target) if spec.targetTimeouts.nonEmpty =>
          addTimeouts(stateEnumInstance.caseHash(target.asInstanceOf[S]), spec.targetTimeouts)
        case Handler.ComputeGoto(leafHash, _) if spec.targetTimeouts.nonEmpty =>
          addTimeouts(leafHash, spec.targetTimeouts)
        case _ =>
      end match
    end for

    new Machine(
      transitions,
      stateTimeouts,
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
    new Machine(Map.empty, Map.empty, Nil, Map.empty, Map.empty, Map.empty, Map.empty, Nil)
end Machine
