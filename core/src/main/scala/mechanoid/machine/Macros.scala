package mechanoid.machine

import scala.quoted.*

/** Macros for the suite-style DSL.
  *
  * This file contains matcher macros and DSL functions. Assembly macros are in AssemblyMacros.scala.
  */
object Macros:

  /** Implementation of `all[T]` - expands sealed type to all leaf children. */
  def allImpl[T: Type](using Quotes): Expr[AllMatcher[T]] =
    import quotes.reflect.*

    val tpe = TypeRepr.of[T]
    val sym = tpe.typeSymbol

    def findLeafCases(s: Symbol): List[Symbol] =
      if !s.exists then Nil
      else
        s.children.flatMap { child =>
          if child.flags.is(Flags.Sealed) then findLeafCases(child)
          else if child.flags.is(Flags.Case) || child.flags.is(Flags.Enum) then List(child)
          else Nil
        }

    def computeHash(fullName: String): Int =
      val normalized = if fullName.endsWith("$") then fullName.dropRight(1) else fullName
      normalized.hashCode

    val leaves =
      if sym.flags.is(Flags.Sealed) then findLeafCases(sym)
      else List(sym)

    if leaves.isEmpty then
      report.errorAndAbort(
        s"Type ${sym.name} has no case children. Use a sealed trait/class or enum.",
        Position.ofMacroExpansion,
      )

    val hashes = leaves.map(c => computeHash(c.fullName))
    val names  = leaves.map(_.name)

    val hashesExpr = Expr(hashes.toSet)
    val namesExpr  = Expr(names)

    '{ new AllMatcher[T]($hashesExpr, $namesExpr) }
  end allImpl

  /** Implementation of `anyOf` for states. */
  inline def anyOfStatesImpl[S](inline first: S, inline rest: S*): AnyOfMatcher[S] =
    ${ anyOfStatesImplMacro[S]('first, 'rest) }

  def anyOfStatesImplMacro[S: Type](
      first: Expr[S],
      rest: Expr[Seq[S]],
  )(using Quotes): Expr[AnyOfMatcher[S]] =
    '{
      val allValues = $first +: $rest
      val hashes    = allValues.map { v =>
        val className = v.getClass.getName.stripSuffix("$").replace('$', '.')
        v match
          case _: scala.reflect.Enum =>
            val caseName = v.toString
            s"$className.$caseName".hashCode
          case _ =>
            className.hashCode
      }.toSet
      val names = allValues.map(_.toString).toList
      new AnyOfMatcher[S](allValues, hashes, names)
    }
  end anyOfStatesImplMacro

  /** Implementation of `anyOf` for events. */
  inline def anyOfEventsImpl[E](inline first: E, inline rest: E*): AnyOfEventMatcher[E] =
    ${ anyOfEventsImplMacro[E]('first, 'rest) }

  def anyOfEventsImplMacro[E: Type](
      first: Expr[E],
      rest: Expr[Seq[E]],
  )(using Quotes): Expr[AnyOfEventMatcher[E]] =
    '{
      val allValues = $first +: $rest
      val hashes    = allValues.map { v =>
        val className = v.getClass.getName.stripSuffix("$").replace('$', '.')
        v match
          case _: scala.reflect.Enum =>
            val caseName = v.toString
            s"$className.$caseName".hashCode
          case _ =>
            className.hashCode
      }.toSet
      val names = allValues.map(_.toString).toList
      new AnyOfEventMatcher[E](allValues, hashes, names)
    }
  end anyOfEventsImplMacro

  /** Implementation of state `via` event - computes hashes at compile time from symbols. */
  inline def stateViaEventImpl[S, E](inline state: S, inline event: E): ViaBuilder[S, E] =
    ${ stateViaEventImplMacro[S, E]('state, 'event) }

  def stateViaEventImplMacro[S: Type, E: Type](
      state: Expr[S],
      event: Expr[E],
  )(using Quotes): Expr[ViaBuilder[S, E]] =
    import quotes.reflect.*

    def extractFullName(expr: Expr[?]): String =
      val term                        = expr.asTerm
      def findSymbol(t: Term): Symbol = t match
        case Ident(_)             => t.symbol
        case Select(_, _)         => t.symbol
        case Inlined(_, _, inner) => findSymbol(inner)
        case Apply(fn, _)         => findSymbol(fn)
        case TypeApply(fn, _)     => findSymbol(fn)
        case _                    => t.symbol

      val sym = findSymbol(term)
      if sym.exists then sym.fullName
      else report.errorAndAbort(s"Cannot extract symbol from expression: ${term.show}")
    end extractFullName

    val stateFullName = extractFullName(state)
    val eventFullName = extractFullName(event)
    val stateHash     = Expr(stateFullName.hashCode)
    val eventHash     = Expr(eventFullName.hashCode)

    '{
      new ViaBuilder[S, E](
        Set($stateHash),
        Set($eventHash),
        List($state.toString),
        List($event.toString),
      )
    }
  end stateViaEventImplMacro

  /** Compute hash for a single value at compile time. */
  inline def computeHashFor[T](inline value: T): Int =
    ${ computeHashForImpl[T]('value) }

  def computeHashForImpl[T: Type](value: Expr[T])(using Quotes): Expr[Int] =
    import quotes.reflect.*

    def findSymbol(t: Term): Symbol = t match
      case Ident(_)             => t.symbol
      case Select(_, _)         => t.symbol
      case Inlined(_, _, inner) => findSymbol(inner)
      case Apply(fn, _)         => findSymbol(fn)
      case TypeApply(fn, _)     => findSymbol(fn)
      case _                    => t.symbol

    val term = value.asTerm
    val sym  = findSymbol(term)

    if sym.exists then Expr(sym.fullName.hashCode)
    else report.errorAndAbort(s"Cannot extract symbol from expression: ${term.show}")
  end computeHashForImpl

  /** Implementation of `event[T]` - creates a type-based event matcher.
    *
    * Returns EventMatcher parameterized with the sealed parent type to enable proper type bounds in `producing`.
    */
  def eventMatcherImpl[E: Type](using Quotes): Expr[EventMatcher[?]] =
    import quotes.reflect.*
    val tpe  = TypeRepr.of[E]
    val sym  = tpe.typeSymbol
    val hash = sym.fullName.hashCode
    val name = sym.name

    // Find the sealed parent type for proper type bounds
    // Check for both Sealed flag (sealed trait/class) and Enum flag (Scala 3 enum)
    def isSealedOrEnum(s: Symbol): Boolean =
      s.flags.is(Flags.Sealed) || s.flags.is(Flags.Enum)

    def findSealedParent(s: Symbol): Option[Symbol] =
      if !s.exists then None
      else if isSealedOrEnum(s) then Some(s)
      else if s.flags.is(Flags.Module) then
        // For enum cases, owner is the companion object (Module)
        // Look for the companion class which should be the sealed enum
        val companion = s.companionClass
        if companion.exists && isSealedOrEnum(companion) then Some(companion)
        else None
      else
        s.owner match
          case owner if owner.isClassDef => findSealedParent(owner)
          case _                         => None

    val parentSym = findSealedParent(sym.owner)

    parentSym match
      case Some(parent) =>
        parent.typeRef.asType match
          case '[p] => '{ new EventMatcher[p](${ Expr(hash) }, ${ Expr(name) }) }
      case None =>
        // No sealed parent found, use E directly
        '{ new EventMatcher[E](${ Expr(hash) }, ${ Expr(name) }) }
  end eventMatcherImpl

  /** Implementation of `state[T]` - creates a type-based state matcher.
    *
    * Returns StateMatcher parameterized with the sealed parent type for consistency with event[T].
    */
  def stateMatcherImpl[S: Type](using Quotes): Expr[StateMatcher[?]] =
    import quotes.reflect.*
    val tpe  = TypeRepr.of[S]
    val sym  = tpe.typeSymbol
    val hash = sym.fullName.hashCode
    val name = sym.name

    // Find the sealed parent type for consistency
    // Check for both Sealed flag (sealed trait/class) and Enum flag (Scala 3 enum)
    def isSealedOrEnum(s: Symbol): Boolean =
      s.flags.is(Flags.Sealed) || s.flags.is(Flags.Enum)

    def findSealedParent(s: Symbol): Option[Symbol] =
      if !s.exists then None
      else if isSealedOrEnum(s) then Some(s)
      else if s.flags.is(Flags.Module) then
        // For enum cases, owner is the companion object (Module)
        // Look for the companion class which should be the sealed enum
        val companion = s.companionClass
        if companion.exists && isSealedOrEnum(companion) then Some(companion)
        else None
      else
        s.owner match
          case owner if owner.isClassDef => findSealedParent(owner)
          case _                         => None

    val parentSym = findSealedParent(sym.owner)

    parentSym match
      case Some(parent) =>
        parent.typeRef.asType match
          case '[p] => '{ new StateMatcher[p](${ Expr(hash) }, ${ Expr(name) }) }
      case None =>
        // No sealed parent found, use S directly
        '{ new StateMatcher[S](${ Expr(hash) }, ${ Expr(name) }) }
  end stateMatcherImpl

end Macros

// ============================================
// Type Matchers for parameterized case classes
// ============================================

/** Matcher for a specific event type (including parameterized case classes). */
final class EventMatcher[E](val hash: Int, val name: String) extends IsMatcher:
  override def toString: String = s"event[$name]"

/** Matcher for a specific state type (including parameterized case classes). */
final class StateMatcher[S](val hash: Int, val name: String) extends IsMatcher:
  override def toString: String = s"state[$name]"

  /** Start building a transition from a state type matcher. */
  inline infix def via[E](inline event: E): ViaBuilder[S, E] =
    val eventHash = Macros.computeHashFor(event)
    val eventName = event.toString
    new ViaBuilder[S, E](Set(hash), Set(eventHash), List(name), List(eventName))

  /** Handle event matcher for parameterized case classes. */
  infix def via[E](eventMatcher: EventMatcher[E]): ViaBuilder[S, E] =
    new ViaBuilder[S, E](Set(hash), Set(eventMatcher.hash), List(name), List(eventMatcher.name))

  /** Handle anyOf events. */
  infix def viaAnyOf[E](events: AnyOfEventMatcher[E]): ViaBuilder[S, E] =
    new ViaBuilder[S, E](Set(hash), events.hashes, List(name), events.names)

  /** Handle all events. */
  infix def viaAll[E](events: AllMatcher[E]): ViaBuilder[S, E] =
    new ViaBuilder[S, E](Set(hash), events.hashes, List(name), events.names)
end StateMatcher

// ============================================
// State value extension for DSL
// ============================================

/** Extension methods for plain state values to use the DSL syntax.
  *
  * This enables `StateValue via Event to Target` syntax for enum cases and case objects.
  *
  * The `NotGiven[S <:< IsMatcher]` constraint ensures these extensions don't conflict with the methods defined on
  * `AllMatcher`, `AnyOfMatcher`, and `StateMatcher`.
  */
extension [S](inline state: S)(using scala.util.NotGiven[S <:< IsMatcher])
  /** Start building a transition: `StateValue via Event to Target` */
  inline infix def via[E](inline event: E): ViaBuilder[S, E] =
    Macros.stateViaEventImpl(state, event)

  /** Alias using >> operator: `StateValue >> Event >> Target` */
  inline def >>[E](inline event: E): ViaBuilder[S, E] =
    Macros.stateViaEventImpl(state, event)

  /** Handle event matcher for parameterized case classes. */
  inline infix def via[E](eventMatcher: EventMatcher[E]): ViaBuilder[S, E] =
    val stateHash = Macros.computeHashFor(state)
    new ViaBuilder[S, E](Set(stateHash), Set(eventMatcher.hash), List(state.toString), List(eventMatcher.name))

  /** Handle anyOf events. */
  inline infix def viaAnyOf[E](events: AnyOfEventMatcher[E]): ViaBuilder[S, E] =
    val stateHash = Macros.computeHashFor(state)
    new ViaBuilder[S, E](Set(stateHash), events.hashes, List(state.toString), events.names)

  /** Handle all events. */
  inline infix def viaAll[E](events: AllMatcher[E]): ViaBuilder[S, E] =
    val stateHash = Macros.computeHashFor(state)
    new ViaBuilder[S, E](Set(stateHash), events.hashes, List(state.toString), events.names)
end extension

// ============================================
// Top-level DSL functions
// ============================================

/** Match ALL children of a sealed parent type.
  *
  * Useful for defining transitions that apply to all subtypes of a sealed trait or enum.
  *
  * @example
  *   {{{
  * sealed trait ProcessingState extends State
  * case object Validating extends ProcessingState
  * case object Charging extends ProcessingState
  *
  * // All processing states can be cancelled
  * all[ProcessingState] via Cancel to Cancelled
  *   }}}
  *
  * @tparam T
  *   A sealed trait, sealed class, or enum
  * @return
  *   A matcher that matches all leaf case children of T
  */
inline def all[T]: AllMatcher[T] = ${ Macros.allImpl[T] }

/** Create a type-based event matcher for parameterized case classes.
  *
  * Useful for matching events by type rather than by value, especially for events that carry data.
  *
  * @example
  *   {{{
  * enum OrderEvent derives Finite:
  *   case Pay(amount: BigDecimal)
  *   case Ship
  *
  * // Match any Pay event, regardless of amount
  * Pending via event[Pay] to Processing
  *   }}}
  *
  * @tparam E
  *   The event type to match
  * @return
  *   An EventMatcher that matches by type
  */
transparent inline def event[E]: EventMatcher[?] = ${ Macros.eventMatcherImpl[E] }

/** Create a type-based state matcher for parameterized case classes.
  *
  * Useful for matching states by type rather than by value, especially for states that carry data.
  *
  * @example
  *   {{{
  * enum OrderState derives Finite:
  *   case Pending
  *   case Failed(reason: String)
  *
  * // Match any Failed state, regardless of reason
  * state[Failed] via Retry to Processing
  *   }}}
  *
  * @tparam S
  *   The state type to match
  * @return
  *   A StateMatcher that matches by type
  */
transparent inline def state[S]: StateMatcher[?] = ${ Macros.stateMatcherImpl[S] }

/** Match multiple specific state values in a single transition.
  *
  * Useful when several states that don't share a common parent should have the same transition.
  *
  * @example
  *   {{{
  * // Multiple states can be archived
  * anyOf(Created, Completed, Cancelled) via Archive to Archived
  *   }}}
  *
  * @tparam S
  *   The state type
  * @param first
  *   The first state value to match
  * @param rest
  *   Additional state values to match
  * @return
  *   A matcher for the specified states
  */
inline def anyOf[S](inline first: S, inline rest: S*): AnyOfMatcher[S] =
  Macros.anyOfStatesImpl(first, rest*)

/** Match multiple specific event values in a single transition.
  *
  * Useful when several events should trigger the same transition.
  *
  * @example
  *   {{{
  * // Multiple events trigger the same transition
  * Idle via anyOfEvents(Click, Tap, Swipe) to Active
  *   }}}
  *
  * @tparam E
  *   The event type
  * @param first
  *   The first event value to match
  * @param rest
  *   Additional event values to match
  * @return
  *   A matcher for the specified events
  */
inline def anyOfEvents[E](inline first: E, inline rest: E*): AnyOfEventMatcher[E] =
  Macros.anyOfEventsImpl(first, rest*)

/** Create a compile-time composable collection of transition specifications.
  *
  * Assembly provides a way to define reusable transition fragments that can be composed together with full compile-time
  * validation.
  *
  * ==Compile-time Validation==
  *
  * The macro validates transitions at compile time:
  *   - Detects duplicate transitions (same state + event combination)
  *   - Allows intentional overrides via `@@ Aspect.overriding`
  *   - Tracks orphan overrides (overrides with nothing to override)
  *
  * ==Usage with Machine==
  *
  * To create a runnable FSM, wrap the assembly in `Machine(...)`:
  * {{{
  * val machine = Machine(assembly[State, Event](
  *   Idle via Start to Running,
  *   Running via Stop to Idle,
  * ))
  * }}}
  *
  * @tparam S
  *   The state type (must derive Finite)
  * @tparam E
  *   The event type (must derive Finite)
  * @param first
  *   The first transition spec or included assembly (required)
  * @param rest
  *   Additional transition specs or included assemblies
  * @return
  *   A composable Assembly
  *
  * @see
  *   [[Machine.apply]] for creating runnable FSMs
  * @see
  *   [[assemblyAll]] for block syntax without commas
  * @see
  *   [[combine]] for composing assemblies with compile-time duplicate detection
  */
transparent inline def assembly[S, E](
    inline first: TransitionSpec[S, E, ?],
    inline rest: TransitionSpec[S, E, ?]*
): Assembly[S, E] =
  ${ AssemblyMacros.assemblyImpl[S, E]('first, 'rest) }

/** Create an assembly using block syntax (no commas between specs).
  *
  * This variant allows mixing val definitions with transition specs. Useful for complex definitions where helper
  * functions are needed.
  *
  * @example
  *   {{{
  * val machine = Machine(assemblyAll[State, Event]:
  *   val timedWaiting = Waiting @@ Aspect.timeout(30.seconds, TimeoutEvent)
  *
  *   Idle via Start to timedWaiting
  *   Running via Stop to Idle
  * )
  *   }}}
  *
  * @tparam S
  *   The state type (must derive Finite)
  * @tparam E
  *   The event type (must derive Finite)
  * @param block
  *   A block containing transition specs and optional val definitions
  * @return
  *   A composable Assembly
  *
  * @see
  *   [[assembly]] for comma-separated syntax
  * @see
  *   [[combine]] for composing assemblies with compile-time duplicate detection
  */
transparent inline def assemblyAll[S, E](
    inline block: Any
): Assembly[S, E] =
  ${ AssemblyMacros.assemblyAllImpl[S, E]('block) }

/** Combine two assemblies into a flattened assembly with compile-time duplicate detection.
  *
  * Both assemblies must be inline expressions (either literal `assembly(...)` calls or `inline def` references). This
  * ensures the macro can inspect both sides for duplicate transitions at compile time.
  *
  * @example
  *   {{{
  * val base = assembly[S, E](Idle via Start to Running)
  * val extra = assembly[S, E](Running via Stop to Idle)
  * val combined = combine(base, extra)
  *   }}}
  *
  * @tparam S
  *   The state type
  * @tparam E
  *   The event type
  * @param left
  *   The first assembly
  * @param right
  *   The second assembly
  * @return
  *   A flattened Assembly containing all specs from both sides
  */
transparent inline def combine[S, E](
    inline left: Assembly[S, E],
    inline right: Assembly[S, E],
): Assembly[S, E] =
  ${ AssemblyMacros.combineImpl[S, E]('left, 'right) }

extension [S, E](inline self: Assembly[S, E])
  /** Combine this assembly with another using `++`, with compile-time duplicate detection.
    *
    * Both sides must be inline expressions (either literal `assembly(...)` calls or `inline def` references).
    *
    * @example
    *   {{{
    * val combined = assembly[S, E](Idle via Start to Running) ++
    *   assembly[S, E](Running via Stop to Idle)
    *   }}}
    */
  transparent inline def ++(inline other: Assembly[S, E]): Assembly[S, E] =
    ${ AssemblyMacros.combineImpl[S, E]('self, 'other) }
end extension
