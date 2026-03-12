package mechanoid.machine

import mechanoid.core.Finite
import zio.ZIO

/** A compile-time composable collection of transition specifications.
  *
  * Assembly provides a way to define reusable transition fragments that can be composed together with full compile-time
  * validation.
  *
  * ==Key Properties==
  *
  *   - '''Compile-time composable''': Assemblies can be combined with `combine`/`++` with full duplicate detection at
  *     compile time
  *   - '''Cannot run''': Assemblies are fragments, not complete FSMs. Use `Machine(assembly)` to create a runnable
  *     Machine
  *   - '''Nested composition''': Assemblies can be combined, flattened at compile time
  *
  * ==Duplicate Detection==
  *
  * The `assembly` macro validates specs and detects duplicate transitions:
  * {{{
  * // COMPILE ERROR - duplicate without override
  * val bad = assembly[S, E](
  *   A via E1 to B,
  *   A via E1 to C,  // ERROR: duplicate
  * )
  *
  * // OK - override explicitly requested on the transition
  * val ok = assembly[S, E](
  *   A via E1 to B,
  *   (A via E1 to C) @@ Aspect.overriding,
  * )
  * }}}
  *
  * ==Composition with combine/++==
  *
  * Assemblies can be combined using `combine()` or `++`. Override conflicts must be resolved at the transition level:
  * {{{
  * val combined = assembly[S, E](A via E1 to B) ++
  *   assembly[S, E]((A via E1 to C) @@ Aspect.overriding)
  * }}}
  *
  * @tparam S
  *   The state type for this assembly
  * @tparam E
  *   The event type for this assembly
  * @param specs
  *   The list of transition specifications in this assembly
  *
  * @see
  *   [[mechanoid.machine.Machine]] for runnable FSMs
  * @see
  *   [[mechanoid.machine.TransitionSpec]] for individual transition definitions
  * @see
  *   [[mechanoid.machine.Aspect.overriding]] for the override aspect
  */
final class Assembly[S, E] private[machine] (
    val specs: List[TransitionSpec[S, E, ?]],
    val hashInfos: List[IncludedHashInfo],
    val orphanOverrides: Set[OrphanInfo] = Set.empty,
    val stateEntryEffects: Map[Int, (E, S) => ZIO[Any, Any, Unit]] = Map.empty,
    val stateExitEffects: Map[Int, (E, S) => ZIO[Any, Any, Unit]] = Map.empty,
):

  /** Register an effect that runs whenever the FSM enters the given state, regardless of which transition caused it.
    *
    * @param state
    *   The state to attach the entry effect to
    * @param f
    *   Effect receiving (triggering event, entered state)
    */
  def onEnter(state: S)(f: (E, S) => ZIO[Any, Any, Unit])(using finite: Finite[S]): Assembly[S, E] =
    val hash = finite.caseHash(state)
    new Assembly(specs, hashInfos, orphanOverrides, stateEntryEffects + (hash -> f), stateExitEffects)

  /** Register an effect that runs whenever the FSM exits the given state, regardless of which transition caused it.
    *
    * @param state
    *   The state to attach the exit effect to
    * @param f
    *   Effect receiving (triggering event, exited state)
    */
  def onExit(state: S)(f: (E, S) => ZIO[Any, Any, Unit])(using finite: Finite[S]): Assembly[S, E] =
    val hash = finite.caseHash(state)
    new Assembly(specs, hashInfos, orphanOverrides, stateEntryEffects, stateExitEffects + (hash -> f))

end Assembly

object Assembly:

  /** Create an assembly from a list of specs with compile-time hash info.
    *
    * This factory method is used by the `assembly` macro to construct Assembly instances. The hashInfos parameter
    * carries compile-time computed hash values for duplicate detection in `combine`/`++`.
    */
  def apply[S, E](
      specs: List[TransitionSpec[S, E, ?]],
      hashInfos: List[IncludedHashInfo],
      orphanOverrides: Set[OrphanInfo] = Set.empty,
      stateEntryEffects: Map[Int, (E, S) => ZIO[Any, Any, Unit]] = Map.empty,
      stateExitEffects: Map[Int, (E, S) => ZIO[Any, Any, Unit]] = Map.empty,
  ): Assembly[S, E] =
    new Assembly(specs, hashInfos, orphanOverrides, stateEntryEffects, stateExitEffects)

end Assembly

/** Hash info for a single transition spec, used for compile-time duplicate detection.
  *
  * Stored in Assembly by the `assembly` macro so that `combine`/`++` can detect duplicates across composed assemblies.
  */
final case class IncludedHashInfo(
    stateHashes: Set[Int],
    eventHashes: Set[Int],
    stateNames: List[String],
    eventNames: List[String],
    targetDesc: String,
    isOverride: Boolean,
)

/** Info about an orphan override (a spec with `@@ Aspect.overriding` that doesn't override anything).
  *
  * Orphan overrides are tracked in Assembly and resolved when assemblies compose. At Machine construction, any
  * remaining orphans trigger compile-time warnings.
  */
final case class OrphanInfo(
    stateHashes: Set[Int],
    eventHashes: Set[Int],
    stateNames: List[String],
    eventNames: List[String],
):
  /** Human-readable description for warning messages. */
  def description: String = s"${stateNames.mkString(",")} via ${eventNames.mkString(",")}"
