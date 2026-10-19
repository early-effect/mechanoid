package mechanoid.machine

import mechanoid.macros.Leaves
import scala.quoted.*

/** Shared utilities for mechanoid macro implementations.
  *
  * This object provides common helpers for AST extraction, type checking, and validation that are used across
  * `assemblyImpl`, `assemblyAllImpl`, `combineImpl`, and `applyImpl` macros.
  *
  * ==Organization==
  *
  *   - Type Constants: Names for type checking
  *   - Type Checking Predicates: `isAssemblyType`, `isMachineType`, etc.
  *   - AST Unwrapping & Extraction: `unwrap`, `extractBoolean`, `qualifiedName`, etc.
  *   - Hash Info Extraction: `SpecHashInfo`, `extractHashInfo`, etc.
  *   - Expression Generation: `hashInfoToExpr`, `orphanInfoToExpr`, etc.
  *   - Duplicate Detection: `checkDuplicates` (wraps pure logic)
  */
private[machine] object MacroUtils:

  // ====== Type Name Constants ======

  val AssemblyTypeName: String       = "mechanoid.machine.Assembly"
  val MachineTypeName: String        = "mechanoid.machine.Machine"
  val TransitionSpecTypeName: String = "TransitionSpec"
  // ====== Type Checking Predicates ======

  /** Check if a type is Assembly[_, _, _] */
  def isAssemblyType(using Quotes)(tpe: quotes.reflect.TypeRepr): Boolean =
    import quotes.reflect.*
    tpe.dealias.widen match
      case AppliedType(base, _) =>
        base.typeSymbol.fullName == AssemblyTypeName
      case _ => false

  /** Check if a type is Machine[_, _, _] */
  def isMachineType(using Quotes)(tpe: quotes.reflect.TypeRepr): Boolean =
    import quotes.reflect.*
    tpe.dealias.widen match
      case AppliedType(base, _) =>
        base.typeSymbol.fullName == MachineTypeName
      case _ => false

  /** Check if a type is TransitionSpec[_, _, _] */
  def isTransitionSpecType(using Quotes)(tpe: quotes.reflect.TypeRepr): Boolean =
    import quotes.reflect.*
    tpe.dealias.widen match
      case AppliedType(base, _) =>
        base.typeSymbol.fullName.contains(TransitionSpecTypeName)
      case _ => false

  // ====== AST Unwrapping & Extraction ======

  /** Unwrap Inlined/Typed/Block wrappers from a term.
    *
    * Many AST nodes come wrapped in layers of `Inlined`, `Typed`, or `Block` nodes. This helper recursively unwraps
    * them to get to the underlying term.
    */
  def unwrap(using Quotes)(term: quotes.reflect.Term): quotes.reflect.Term =
    import quotes.reflect.*
    term match
      case Inlined(_, _, inner) => unwrap(inner)
      case Typed(inner, _)      => unwrap(inner)
      case Block(_, expr)       => unwrap(expr)
      case other                => other

  /** Extract boolean value from a term.
    *
    * Handles wrapped boolean literals like `Inlined(_, _, Literal(BooleanConstant(true)))`.
    */
  def extractBoolean(using Quotes)(term: quotes.reflect.Term): Boolean =
    import quotes.reflect.*
    unwrap(term) match
      case Literal(BooleanConstant(b)) => b
      case _                           => false

  /** Extract qualified name from a symbol (last 2 parts of fullName).
    *
    * For example, `experiments.TestState.A` becomes `TestState.A`.
    */
  def qualifiedName(using Quotes)(sym: quotes.reflect.Symbol): String =
    val parts = sym.fullName.split('.').toList.map(_.replace("$", "").trim).filter(_.nonEmpty)
    parts.takeRight(2).mkString(".")

  // ====== AST Extraction Helpers ======

  /** Extract int literals from a Set expression like Set(hash1, hash2).
    *
    * Uses a TreeAccumulator to find all IntConstant literals in the term.
    */
  def extractSetInts(using Quotes)(setTerm: quotes.reflect.Term): Set[Int] =
    import quotes.reflect.*
    val ints = scala.collection.mutable.Set[Int]()
    object Collector extends TreeAccumulator[Unit]:
      def foldTree(u: Unit, tree: Tree)(owner: Symbol): Unit =
        tree match
          case Literal(IntConstant(v)) => ints += v
          case _                       => foldOverTree((), tree)(owner)
    Collector.foldTree((), setTerm)(Symbol.spliceOwner)
    ints.toSet
  end extractSetInts

  /** Extract string literals from a List expression like List("name1", "name2").
    *
    * Uses a TreeAccumulator to find all non-empty StringConstant literals in the term. Also handles `.toString()` calls
    * on enum values like `TestState.A.toString()` by extracting the symbol name.
    */
  def extractListStrings(using Quotes)(listTerm: quotes.reflect.Term): List[String] =
    import quotes.reflect.*
    val strs = scala.collection.mutable.ListBuffer[String]()

    // Extract qualified name from a term using its symbol's full name
    // Returns the last two meaningful parts (Type.Value) for enum values (e.g., "TestState.A")
    def extractQualifiedName(base: Term): Option[String] =
      val unwrapped = unwrap(base)
      // Use symbol's full name to get the qualified path
      val fullName =
        if unwrapped.symbol.exists then unwrapped.symbol.fullName
        else unwrapped.show // Fallback to show if no symbol

      if fullName.isEmpty then None
      else
        // Split by . and take last 2 meaningful parts, clean up $ characters
        val parts      = fullName.split('.').toList.map(_.replace("$", "").trim).filter(_.nonEmpty)
        val meaningful = parts.takeRight(2)
        Some(meaningful.mkString("."))
    end extractQualifiedName

    object Collector extends TreeAccumulator[Unit]:
      def foldTree(u: Unit, tree: Tree)(owner: Symbol): Unit =
        tree match
          case Literal(StringConstant(v)) if v.nonEmpty => strs += v
          // Handle EnumValue.toString() pattern - extract the qualified enum value name
          // Pattern: Apply(Select(Select/Ident, "toString"), Nil)
          case Apply(Select(base, "toString"), Nil) =>
            extractQualifiedName(base) match
              case Some(name) => strs += name
              case None       => foldOverTree((), tree)(owner)
          // Also handle toString with empty type args: Apply(TypeApply(Select(..., "toString"), _), Nil)
          case Apply(TypeApply(Select(base, "toString"), _), Nil) =>
            extractQualifiedName(base) match
              case Some(name) => strs += name
              case None       => foldOverTree((), tree)(owner)
          case _ => foldOverTree((), tree)(owner)
    end Collector
    Collector.foldTree((), listTerm)(Symbol.spliceOwner)
    strs.toList
  end extractListStrings

  /** Extract List elements from a List constructor term.
    *
    * Handles various forms:
    *   - `List(elem1, elem2, ...)`
    *   - `scala.List.apply(...)`
    *   - `Nil`
    *   - Typed/Inlined wrappers
    *   - Scala 3 Repeated nodes for varargs
    */
  def extractListElements(using Quotes)(listTerm: quotes.reflect.Term): List[quotes.reflect.Term] =
    import quotes.reflect.*

    // Helper to unwrap elements from potential Repeated/Typed wrappers (Scala 3 varargs)
    def unwrapElements(elems: List[Term]): List[Term] =
      elems.flatMap {
        case Repeated(innerElems, _) =>
          innerElems.flatMap(e => unwrapElements(List(e)))
        case Typed(Repeated(innerElems, _), _) =>
          // Handle Typed(Repeated(...), _) - common from Expr.ofList
          innerElems.flatMap(e => unwrapElements(List(e)))
        case Typed(inner, tpt) =>
          // Check if this is a Seq/repeated type wrapper
          if tpt.show.contains("Seq[") || tpt.show.contains("<repeated>") then unwrapElements(List(inner))
          else List(inner)
        case other =>
          List(other)
      }

    val result = listTerm match
      // Handle .flatten[T] call: List(List(a), List(b)).flatten[T]
      case Apply(TypeApply(Select(innerList, "flatten"), _), _) =>
        extractListElements(innerList).flatMap(elem => extractListElements(elem))
      // Handle .flatten call without type args: List(List(a), List(b)).flatten
      case Apply(Select(innerList, "flatten"), _) =>
        extractListElements(innerList).flatMap(elem => extractListElements(elem))
      case Apply(TypeApply(Select(Ident("List"), "apply"), _), elems)                  => unwrapElements(elems)
      case Apply(TypeApply(Select(Select(Ident("scala"), "List"), "apply"), _), elems) => unwrapElements(elems)
      case Apply(TypeApply(Select(Ident("Nil"), ":::"), _), List(other))               => extractListElements(other)
      case Apply(Select(Ident("List"), "apply"), elems)                                => unwrapElements(elems)
      case Apply(Select(Select(Ident("scala"), "List"), "apply"), elems)               => unwrapElements(elems)
      case Select(Ident("scala"), "Nil")                                               => Nil
      case Ident("Nil")                                                                => Nil
      case Typed(inner, _)                                                             => extractListElements(inner)
      case Inlined(_, _, inner)                                                        => extractListElements(inner)
      case other                                                                       =>
        // Handle SeqLiteral by checking class name (no extractor in quotes API)
        if other.getClass.getName.contains("SeqLiteral") then
          // SeqLiteral is a varargs sequence - extract children via tree traversal
          val children = scala.collection.mutable.ListBuffer[Term]()
          object ChildCollector extends TreeAccumulator[Unit]:
            def foldTree(u: Unit, tree: Tree)(owner: Symbol): Unit =
              tree match
                case t: Term if t ne other =>
                  // Only collect direct children that look like specs
                  if t.tpe.dealias.widen.show.contains("TransitionSpec") then children += t
                  else foldOverTree((), tree)(owner)
                case _ => foldOverTree((), tree)(owner)
          ChildCollector.foldTree((), other)(Symbol.spliceOwner)
          children.toList
        else Nil
    result
  end extractListElements

  // ====== Hash Info Extraction ======

  /** Compile-time info extracted from a TransitionSpec expression for duplicate detection. */
  case class SpecHashInfo(
      stateHashes: Set[Int],
      eventHashes: Set[Int],
      stateNames: List[String],
      eventNames: List[String],
      targetDesc: String,
      isOverride: Boolean,
      sourceDesc: String,
  )

  /** Extract target name from a term (for error messages). */
  def extractTargetName(using Quotes)(t: quotes.reflect.Term): String =
    import quotes.reflect.*
    t match
      case Select(_, name)      => name
      case Ident(name)          => name
      case Inlined(_, _, inner) => extractTargetName(inner.asInstanceOf[Term])
      case _                    => "?"

  /** Extract hash info from unexpanded DSL pattern like `A via E1 to B`.
    *
    * The DSL inline macros (via, to, etc.) are NOT expanded when the outer macro sees them. So we see patterns like:
    * `via[S](A)(NotGiven.value)[E](E1).to[S](B)`
    *
    * This function walks the AST to find:
    *   - Terminal methods: `to`, `stay`, `stop`, `emitting`
    *   - The `via` call with state and event
    *   - Computes hashes from symbol fullNames
    */
  def extractHashInfoFromDSL(using Quotes)(term: quotes.reflect.Term): Option[SpecHashInfo] =
    import quotes.reflect.*

    // Same full-name hash the matchers emit, including the module-class '$' strip.
    def computeSymbolHash(sym: Symbol): Int =
      Macros.hashFullName(sym.fullName)

    // Extract symbol from a term that represents a state or event value
    def extractValueSymbol(t: Term): Option[Symbol] =
      def usable(sym: Symbol): Option[Symbol] =
        if sym.exists && !sym.flags.is(Flags.Method) && sym.name != "<init>" then Some(sym) else None
      t match
        case Ident(_)             => usable(t.symbol)
        case Select(_, _)         => usable(t.symbol)
        case Inlined(_, _, inner) => extractValueSymbol(inner)
        case Typed(inner, _)      => extractValueSymbol(inner)
        case _                    => None

    // Extract all state symbols from a term (handles single values and anyOf patterns)
    def extractStateSymbols(t: Term): Set[Symbol] =
      t match
        case Apply(TypeApply(Ident("anyOf"), _), args) =>
          args.flatMap(extractValueSymbol).toSet
        case Apply(Ident("anyOf"), args) =>
          args.flatMap(extractValueSymbol).toSet
        case other =>
          extractValueSymbol(other).toSet

    // Extract all event symbols from a term
    def extractEventSymbols(t: Term): Set[Symbol] =
      t match
        case Apply(TypeApply(Ident("anyOfEvents"), _), args) =>
          args.flatMap(extractValueSymbol).toSet
        case Apply(Ident("anyOfEvents"), args) =>
          args.flatMap(extractValueSymbol).toSet
        case Apply(TypeApply(Ident("event"), _), _) =>
          // event[T] matches by type - we need to extract the type parameter
          Set.empty // Will be handled by type extraction
        case other =>
          extractValueSymbol(other).toSet

    // Extract type symbol from event[T] pattern
    def extractEventTypeSymbol(t: Term): Option[Symbol] =
      t match
        case Apply(TypeApply(Ident("event"), List(tpt)), _) =>
          Some(tpt.tpe.typeSymbol)
        case Apply(TypeApply(fn, List(tpt)), _) if fn.show.contains("event") =>
          Some(tpt.tpe.typeSymbol)
        case _ => None

    // Try to find via/to/stay/stop pattern in the AST
    var stateSymbols    = Set.empty[Symbol]
    var eventSymbols    = Set.empty[Symbol]
    var targetDesc      = "?"
    var isOverride      = false
    var foundTransition = false

    object DSLFinder extends TreeAccumulator[Unit]:
      def foldTree(u: Unit, tree: Tree)(owner: Symbol): Unit =
        tree match
          // Match @@ for override detection
          case Apply(Select(base, "@@"), List(aspectArg)) =>
            if aspectArg.show.contains("overriding") then isOverride = true
            foldTree((), base)(owner)

          // Match extension method @@ pattern: Apply(Apply(inner, [specArg]), [aspectArg])
          case Apply(Apply(inner, specArgs), aspectArgs) if isAtAtMethod(inner) && aspectArgs.nonEmpty =>
            if aspectArgs.head.show.contains("overriding") then isOverride = true
            // Continue traversal on the spec argument, not the whole tree
            if specArgs.nonEmpty then foldTree((), specArgs.head)(owner)
            else foldOverTree((), tree)(owner)

          // Match to stay { f } / to(target)(f)
          case Apply(Apply(Select(qual, "to"), List(targetArg)), _) =>
            foundTransition = true
            val name = extractTargetName(targetArg)
            targetDesc =
              if name == "stay" || targetArg.symbol.name == "stay" then "stay"
              else if name == "?" then "-> ?"
              else s"-> $name"
            foldTree((), qual)(owner)

          // Match .to[Leaf] { f } → ComputeTo.apply
          case Apply(Select(TypeApply(Select(qual, "to"), List(tpt)), "apply"), _) =>
            foundTransition = true
            targetDesc = s"-> ${tpt.tpe.typeSymbol.name}"
            foldTree((), qual)(owner)

          // Match to[Leaf] { f }
          case Apply(TypeApply(Select(qual, "to"), List(tpt)), _) =>
            foundTransition = true
            targetDesc = s"-> ${tpt.tpe.typeSymbol.name}"
            foldTree((), qual)(owner)

          // Match .to(target) - DSL terminal with target
          case Apply(TypeApply(Select(qual, "to"), _), List(targetArg)) =>
            foundTransition = true
            targetDesc = extractTargetName(targetArg) match
              case "?" => "-> ?"
              case n   => s"-> $n"
            foldTree((), qual)(owner)

          case Apply(Select(qual, "to"), List(targetArg)) =>
            foundTransition = true
            targetDesc = extractTargetName(targetArg) match
              case "?" => "-> ?"
              case n   => s"-> $n"
            foldTree((), qual)(owner)

          // Match .stay - DSL terminal
          case Select(qual, "stay") =>
            foundTransition = true
            targetDesc = "stay"
            foldTree((), qual)(owner)

          // Match .stop - DSL terminal
          case Select(qual, "stop") =>
            foundTransition = true
            targetDesc = "stop"
            foldTree((), qual)(owner)

          // Match .emitting(...) - continues the chain
          case Apply(Select(qual, "emitting"), _) =>
            foldTree((), qual)(owner)

          case Apply(TypeApply(Select(qual, "emitting"), _), _) =>
            foldTree((), qual)(owner)

          // Match via[S](state)(using NotGiven)[E](event) pattern
          // This is the complex DSL chain: via[S](A)(NotGiven.value)[E](E1)
          case Apply(TypeApply(Apply(Apply(TypeApply(viaFn, _), List(stateArg)), _), _), List(eventArg))
              if viaFn.show.contains("via") =>
            val stateSyms = extractStateSymbols(stateArg)
            stateSymbols = stateSymbols ++ stateSyms

            // Check if event is event[T] pattern
            extractEventTypeSymbol(eventArg) match
              case Some(typeSym) =>
                eventSymbols = eventSymbols + typeSym
              case None =>
                eventSymbols = eventSymbols ++ extractEventSymbols(eventArg)

            foldOverTree((), tree)(owner)

          // Match simpler via patterns
          case Apply(TypeApply(Select(_, "via"), _), List(eventArg)) =>
            extractEventTypeSymbol(eventArg) match
              case Some(typeSym) =>
                eventSymbols = eventSymbols + typeSym
              case None =>
                eventSymbols = eventSymbols ++ extractEventSymbols(eventArg)
            foldOverTree((), tree)(owner)

          case Apply(Select(_, "via"), List(eventArg)) =>
            extractEventTypeSymbol(eventArg) match
              case Some(typeSym) =>
                eventSymbols = eventSymbols + typeSym
              case None =>
                eventSymbols = eventSymbols ++ extractEventSymbols(eventArg)
            foldOverTree((), tree)(owner)

          // Match Ident that represents state (e.g., in via[S](A))
          case Ident(name) if tree.symbol.exists && !tree.symbol.isPackageDef && !tree.symbol.isClassDef =>
            // This might be a state reference in via(state)
            foldOverTree((), tree)(owner)

          case Inlined(_, _, inner) =>
            foldTree((), inner)(owner)

          case _ =>
            foldOverTree((), tree)(owner)
    end DSLFinder

    DSLFinder.foldTree((), term)(Symbol.spliceOwner)

    if foundTransition && (stateSymbols.nonEmpty || eventSymbols.nonEmpty) then
      val stateHashes = stateSymbols.map(computeSymbolHash)
      val eventHashes = eventSymbols.map(computeSymbolHash)
      val stateNames  = stateSymbols.map(qualifiedName).toList
      val eventNames  = eventSymbols.map(qualifiedName).toList
      Some(SpecHashInfo(stateHashes, eventHashes, stateNames, eventNames, targetDesc, isOverride, "?"))
    else None
  end extractHashInfoFromDSL

  private val AllMatcherName        = "mechanoid.machine.AllMatcher"
  private val EventMatcherName      = "mechanoid.machine.EventMatcher"
  private val StateMatcherName      = "mechanoid.machine.StateMatcher"
  private val AnyOfMatcherName      = "mechanoid.machine.AnyOfMatcher"
  private val AnyOfEventMatcherName = "mechanoid.machine.AnyOfEventMatcher"
  private val DenotedMatcherNames   = Set(AllMatcherName, EventMatcherName, StateMatcherName)

  private def leafLabel(name: String): String =
    if name.endsWith("$") then name.dropRight(1) else name

  private def packLeaves(using Quotes)(syms: List[quotes.reflect.Symbol]): (Set[Int], List[String]) =
    (
      syms.map(sym => Macros.hashFullName(sym.fullName)).toSet,
      syms.map(sym => leafLabel(sym.name)),
    )

  private def denotePacked(using Quotes)(tpe: quotes.reflect.TypeRepr): Option[(Set[Int], List[String])] =
    Leaves.denote(tpe) match
      case Right(leaves) if leaves.nonEmpty => Some(packLeaves(leaves))
      case _                                => None

  private def valueLeaf(using Quotes)(term: quotes.reflect.Term): Option[quotes.reflect.Symbol] =
    import quotes.reflect.*
    val sym = Leaves.leafSymbol(unwrap(term).tpe)
    if sym.exists && !sym.flags.is(Flags.Method) &&
      (sym.flags.is(Flags.Case) || sym.flags.is(Flags.Enum) || sym.flags.is(Flags.Module))
    then Some(sym)
    else None

  /** Leaves a matcher or a value term denotes. Type matchers use `Leaves.denote`, not the `<init>` symbol. */
  private def matcherSide(using Quotes)(term: quotes.reflect.Term): Option[(Set[Int], List[String])] =
    import quotes.reflect.*
    val t = unwrap(term)

    def denotedCall(name: String, tpe: TypeRepr): Option[(Set[Int], List[String])] =
      name match
        case "all" | "state" | "event" => denotePacked(tpe)
        case _                         => None

    def flattenArgs(args: List[Term]): List[Term] =
      args.flatMap {
        case Repeated(elems, _)           => flattenArgs(elems)
        case Typed(Repeated(elems, _), _) => flattenArgs(elems)
        case Typed(inner, _)              => flattenArgs(List(inner))
        case Inlined(_, _, inner)         => flattenArgs(List(inner))
        case other                        => List(other)
      }

    def anyOfSide(fn: Term, args: List[Term]): Option[(Set[Int], List[String])] =
      fn.symbol.name match
        case "anyOf" | "anyOfEvents" =>
          val syms = flattenArgs(args).flatMap(valueLeaf)
          if syms.nonEmpty then Some(packLeaves(syms)) else None
        case _ => None

    def typeArg(tpe: TypeRepr): Option[TypeRepr] =
      tpe.dealias match
        case AppliedType(_, List(arg)) => Some(arg)
        case _                         => None

    def fromType(tpe: TypeRepr): Option[(Set[Int], List[String])] =
      tpe.dealias match
        case AppliedType(con, List(arg)) if DenotedMatcherNames.contains(con.typeSymbol.fullName) =>
          denotePacked(arg)
        case _ => None

    def ctorSide(
        tpt: TypeTree,
        tpts: List[TypeTree],
        args: List[Term],
        tpe: TypeRepr,
    ): Option[(Set[Int], List[String])] =
      val fromTpt = tpt.tpe.typeSymbol.fullName
      val full    =
        if DenotedMatcherNames.contains(fromTpt) || fromTpt == AnyOfMatcherName || fromTpt == AnyOfEventMatcherName
        then fromTpt
        else
          tpe.dealias match
            case AppliedType(con, _) => con.typeSymbol.fullName
            case _                   => fromTpt
      if DenotedMatcherNames.contains(full) then
        tpts.headOption
          .flatMap(tp => denotePacked(tp.tpe))
          .orElse(typeArg(tpt.tpe).flatMap(denotePacked))
          .orElse(typeArg(tpe).flatMap(denotePacked))
      else if full == AnyOfMatcherName || full == AnyOfEventMatcherName then
        val hashes = if args.length >= 2 then extractSetInts(args(1)) else Set.empty[Int]
        val names  = if args.length >= 3 then extractListStrings(args(2)).map(leafLabel) else Nil
        if hashes.nonEmpty then Some((hashes, names)) else None
      else None
      end if
    end ctorSide

    t match
      case TypeApply(fn, List(tpt)) =>
        denotedCall(fn.symbol.name, tpt.tpe)
      case Apply(TypeApply(Select(New(tpt), "<init>"), tpts), args) =>
        ctorSide(tpt, tpts, args, t.tpe)
      case Apply(Select(New(tpt), "<init>"), args) =>
        ctorSide(tpt, Nil, args, t.tpe)
      case Apply(TypeApply(fn, List(tpt)), args) =>
        denotedCall(fn.symbol.name, tpt.tpe)
          .orElse(anyOfSide(fn, args))
          .orElse(fromType(t.tpe))
          .orElse(valueLeaf(t).map(sym => packLeaves(List(sym))))
      case Apply(fn, args) =>
        anyOfSide(fn, args).orElse(fromType(t.tpe)).orElse(valueLeaf(t).map(sym => packLeaves(List(sym))))
      case _ =>
        fromType(t.tpe).orElse(valueLeaf(t).map(sym => packLeaves(List(sym))))
    end match
  end matcherSide

  private def isMatcherTerm(using Quotes)(term: quotes.reflect.Term): Boolean =
    import quotes.reflect.*
    val t                         = unwrap(term)
    def called(fn: Term): Boolean =
      fn.symbol.name match
        case "all" | "state" | "event" | "anyOf" | "anyOfEvents" => true
        case "<init>"                                            =>
          def selectNew(term: Term): Option[TypeTree] =
            term match
              case Select(New(tpt), _) => Some(tpt)
              case TypeApply(inner, _) => selectNew(inner)
              case _                   => None
          selectNew(fn) match
            case Some(tpt) =>
              val full = tpt.tpe.typeSymbol.fullName
              DenotedMatcherNames.contains(full) || full == AnyOfMatcherName || full == AnyOfEventMatcherName
            case None => false
        case _ => false
    t match
      case TypeApply(fn, _)           => called(fn)
      case Apply(TypeApply(fn, _), _) => called(fn)
      case Apply(fn, _)               => called(fn)
      case _                          =>
        t.tpe.dealias match
          case AppliedType(con, _) =>
            val full = con.typeSymbol.fullName
            DenotedMatcherNames.contains(full) || full == AnyOfMatcherName || full == AnyOfEventMatcherName
          case _ => false
    end match
  end isMatcherTerm

  /** `via` / `viaAll` / `viaAnyOf` whose receiver or argument is a matcher. Both sides are denotations. */
  private def extractDenotedSpec(using Quotes)(term: quotes.reflect.Term): (Option[SpecHashInfo], String) =
    import quotes.reflect.*
    var targetDesc = "?"
    var isOverride = false
    var state      = Option.empty[(Set[Int], List[String])]
    var event      = Option.empty[(Set[Int], List[String])]
    var sawMatcher = false

    def describeTarget(target: Term): String =
      val name = extractTargetName(unwrap(target))
      if name == "stay" || unwrap(target).symbol.name == "stay" then "stay"
      else if name == "stop" || unwrap(target).symbol.name == "stop" then "stop"
      else if name == "?" then "-> ?"
      else s"-> $name"

    def consider(method: String, receiver: Term, arg: Term): Unit =
      if (method == "via" || method == "viaAll" || method == "viaAnyOf") && state.isEmpty then
        if isMatcherTerm(receiver) || isMatcherTerm(arg) then
          sawMatcher = true
          state = matcherSide(receiver)
          event = matcherSide(arg)

    def walk(tree: Tree): Unit =
      tree match
        case Inlined(_, bindings, inner) =>
          bindings.foreach(walk)
          walk(inner)
        case Typed(inner, _)    => walk(inner)
        case Block(stats, expr) =>
          stats.foreach(walk)
          walk(expr)
        case ValDef(_, _, Some(rhs))                 => walk(rhs)
        case Apply(Select(base, "@@"), List(aspect)) =>
          if aspect.show.contains("overriding") then isOverride = true
          walk(base)
        case Apply(Apply(inner, specArgs), aspectArgs) if isAtAtMethod(inner) && aspectArgs.nonEmpty =>
          if aspectArgs.head.show.contains("overriding") then isOverride = true
          specArgs.foreach(walk)
        case Apply(Apply(TypeApply(Select(qual, "to"), _), List(target)), _) =>
          targetDesc = describeTarget(target)
          walk(qual)
        case Apply(Apply(Select(qual, "to"), List(target)), _) =>
          targetDesc = describeTarget(target)
          walk(qual)
        case Apply(TypeApply(Select(TypeApply(Select(qual, "to"), List(tpt)), "apply"), _), _) =>
          targetDesc = s"-> ${leafLabel(tpt.symbol.name)}"
          walk(qual)
        case Apply(Select(TypeApply(Select(qual, "to"), List(tpt)), "apply"), _) =>
          targetDesc = s"-> ${leafLabel(tpt.symbol.name)}"
          walk(qual)
        case Apply(TypeApply(Select(qual, "to"), List(tpt)), List(target)) =>
          val terminal = describeTarget(target)
          targetDesc = if terminal == "stay" || terminal == "stop" || tpt.symbol.name == "<none>" then terminal
          else s"-> ${leafLabel(tpt.symbol.name)}"
          walk(qual)
        case Apply(TypeApply(Select(qual, "to"), _), List(target)) =>
          targetDesc = describeTarget(target)
          walk(qual)
        case Apply(Select(qual, "to"), List(target)) =>
          targetDesc = describeTarget(target)
          walk(qual)
        case Apply(TypeApply(Select(New(tpt), "<init>"), _), args) if tpt.show.contains("ComputeTo") =>
          if args.length >= 6 then
            unwrap(args(5)) match
              case Literal(StringConstant(name)) => targetDesc = s"-> $name"
              case _                             => ()
          args.foreach(walk)
        case Apply(Select(New(tpt), "<init>"), args) if tpt.show.contains("ComputeTo") =>
          if args.length >= 6 then
            unwrap(args(5)) match
              case Literal(StringConstant(name)) => targetDesc = s"-> $name"
              case _                             => ()
          args.foreach(walk)
        case Apply(TypeApply(Select(receiver, method), _), List(arg)) =>
          consider(method, receiver, arg)
          walk(receiver)
          walk(arg)
        case Apply(Select(receiver, method), List(arg)) =>
          consider(method, receiver, arg)
          walk(receiver)
          walk(arg)
        case Apply(fun, args) =>
          walk(fun)
          args.foreach(walk)
        case TypeApply(fun, _) => walk(fun)
        case Select(qual, _)   => walk(qual)
        case _                 => ()

    walk(term)
    val info = (state, event) match
      case (Some(stateSide), Some(eventSide)) if sawMatcher && stateSide._1.nonEmpty && eventSide._1.nonEmpty =>
        Some(SpecHashInfo(stateSide._1, eventSide._1, stateSide._2, eventSide._2, targetDesc, isOverride, "?"))
      case _ => None
    (info, targetDesc)
  end extractDenotedSpec

  /** Extract hash info from a TransitionSpec term (either inline expr or val definition).
    *
    * Uses a TreeAccumulator to find ViaBuilder, TransitionSpec, AllMatcher constructors and extract their compile-time
    * hash values. Also handles unexpanded DSL patterns like `A via E1 to B`.
    */
  def extractHashInfo(using Quotes)(term: quotes.reflect.Term): Option[SpecHashInfo] =
    import quotes.reflect.*

    // A matcher call still in the tree. Read both sides from the type, not from `<init>`.
    val (denotedInfo, denotedTarget) = extractDenotedSpec(term)
    denotedInfo match
      case Some(info) => return Some(info)
      case None       => ()

    def allMatcherLiterals(root: Term): (Set[Int], List[String]) =
      var hashes = Set.empty[Int]
      var names  = List.empty[String]
      object Walk extends TreeAccumulator[Unit]:
        def foldTree(u: Unit, tree: Tree)(owner: Symbol): Unit =
          tree match
            case Apply(TypeApply(Select(New(tpt), "<init>"), _), args)
                if args.length >= 2 && tpt.show.contains("AllMatcher") =>
              val found = extractSetInts(args(0))
              if found.nonEmpty then
                hashes = found
                names = extractListStrings(args(1))
              foldOverTree((), tree)(owner)
            case Apply(Select(New(tpt), "<init>"), args) if args.length >= 2 && tpt.show.contains("AllMatcher") =>
              val found = extractSetInts(args(0))
              if found.nonEmpty then
                hashes = found
                names = extractListStrings(args(1))
              foldOverTree((), tree)(owner)
            case _ =>
              foldOverTree((), tree)(owner)
      end Walk
      Walk.foldTree((), root)(Symbol.spliceOwner)
      (hashes, names)
    end allMatcherLiterals

    val (matcherHashes, matcherNames) = allMatcherLiterals(term)

    def localIntVals(root: Term): Map[String, Int] =
      var bound                       = Map.empty[String, Int]
      def intOf(t: Term): Option[Int] =
        t match
          case Literal(IntConstant(v)) => Some(v)
          case Typed(inner, _)         => intOf(inner)
          case Inlined(_, _, inner)    => intOf(inner)
          case Block(_, expr)          => intOf(expr)
          case _                       => None
      object Walk extends TreeAccumulator[Unit]:
        def foldTree(u: Unit, tree: Tree)(owner: Symbol): Unit =
          tree match
            case ValDef(name, _, Some(rhs)) =>
              intOf(rhs).foreach(v => bound = bound.updated(name, v))
              foldOverTree((), tree)(owner)
            case _ =>
              foldOverTree((), tree)(owner)
      end Walk
      Walk.foldTree((), root)(Symbol.spliceOwner)
      bound
    end localIntVals

    val intVals = localIntVals(term)

    val stringVals: Map[String, String] =
      var bound                             = Map.empty[String, String]
      def stringOf(t: Term): Option[String] =
        unwrap(t) match
          case Literal(StringConstant(s))           => Some(s)
          case Apply(Select(qual, "toString"), Nil) =>
            valueLeaf(qual).map(sym => leafLabel(sym.name))
          case _ => None
      object Walk extends TreeAccumulator[Unit]:
        def foldTree(u: Unit, tree: Tree)(owner: Symbol): Unit =
          tree match
            case ValDef(name, _, Some(rhs)) =>
              stringOf(rhs).foreach(value => bound = bound.updated(name, value))
              foldOverTree((), tree)(owner)
            case _ =>
              foldOverTree((), tree)(owner)
      end Walk
      Walk.foldTree((), term)(Symbol.spliceOwner)
      bound
    end stringVals

    def resolveInts(setTerm: Term): Set[Int] =
      val direct = extractSetInts(setTerm)
      var extra  = Set.empty[Int]
      object Ids extends TreeAccumulator[Unit]:
        def foldTree(u: Unit, tree: Tree)(owner: Symbol): Unit =
          tree match
            case Ident(name) =>
              intVals.get(name).foreach(v => extra += v)
              foldOverTree((), tree)(owner)
            case _ =>
              foldOverTree((), tree)(owner)
      end Ids
      Ids.foldTree((), setTerm)(Symbol.spliceOwner)
      direct ++ extra
    end resolveInts

    def withMatcherLeaves(stateHashes: Set[Int], stateNames: List[String]): (Set[Int], List[String]) =
      if stateHashes.nonEmpty then (stateHashes, stateNames)
      else if matcherHashes.nonEmpty then
        val names = if stateNames.nonEmpty then stateNames else matcherNames
        (matcherHashes, names)
      else (stateHashes, stateNames)

    val matcherBindings: Map[String, (Set[Int], List[String])] =
      var bound = Map.empty[String, (Set[Int], List[String])]
      object BindWalk extends TreeAccumulator[Unit]:
        def foldTree(u: Unit, tree: Tree)(owner: Symbol): Unit =
          tree match
            case ValDef(name, _, Some(rhs)) =>
              if isMatcherTerm(rhs) then matcherSide(rhs).foreach(side => bound = bound.updated(name, side))
              foldOverTree((), tree)(owner)
            case _ =>
              foldOverTree((), tree)(owner)
      end BindWalk
      BindWalk.foldTree((), term)(Symbol.spliceOwner)
      bound
    end matcherBindings

    def identsOf(root: Term): Set[String] =
      var names = Set.empty[String]
      object Ids extends TreeAccumulator[Unit]:
        def foldTree(u: Unit, tree: Tree)(owner: Symbol): Unit =
          tree match
            case Ident(name) =>
              names += name
              foldOverTree((), tree)(owner)
            case _ =>
              foldOverTree((), tree)(owner)
      end Ids
      Ids.foldTree((), root)(Symbol.spliceOwner)
      names
    end identsOf

    def nestedCtor(arg: Term): Option[(Set[Int], List[String])] =
      var found = Option.empty[(Set[Int], List[String])]
      object Walk extends TreeAccumulator[Unit]:
        def foldTree(u: Unit, tree: Tree)(owner: Symbol): Unit =
          if found.isEmpty then
            tree match
              case t: Term if isMatcherTerm(t) =>
                matcherSide(t) match
                  case Some(side) if side._1.nonEmpty => found = Some(side)
                  case _                              => foldOverTree((), tree)(owner)
              case _ =>
                foldOverTree((), tree)(owner)
          else ()
      end Walk
      Walk.foldTree((), arg)(Symbol.spliceOwner)
      found
    end nestedCtor

    def sideFromArg(arg: Term): Option[(Set[Int], List[String])] =
      val boundSides   = identsOf(arg).toList.flatMap(matcherBindings.get)
      val boundHashes  = boundSides.flatMap(_._1).toSet
      val hashes       = boundHashes ++ resolveInts(arg)
      val literalNames = extractListStrings(arg)
      val boundNames   = boundSides.flatMap(_._2)
      val nested       = if isMatcherTerm(arg) then matcherSide(arg) else nestedCtor(arg)
      val chosenHashes =
        if hashes.nonEmpty then hashes
        else nested.fold(Set.empty[Int])(_._1)
      val chosenNames =
        if literalNames.nonEmpty then literalNames
        else if boundNames.nonEmpty then boundNames
        else nested.fold(List.empty[String])(_._2)
      if chosenHashes.nonEmpty then Some((chosenHashes, chosenNames)) else None
    end sideFromArg

    def viaBuilderSides: (Option[(Set[Int], List[String])], Option[(Set[Int], List[String])]) =
      var stateSide                        = Option.empty[(Set[Int], List[String])]
      var eventSide                        = Option.empty[(Set[Int], List[String])]
      def namesOf(arg: Term): List[String] =
        val literals = extractListStrings(arg)
        if literals.nonEmpty then literals
        else
          val fromVals = identsOf(arg).toList.flatMap(stringVals.get)
          if fromVals.nonEmpty then fromVals
          else identsOf(arg).toList.flatMap(matcherBindings.get).flatMap(_._2)

      def named(side: Option[(Set[Int], List[String])], names: List[String]): Option[(Set[Int], List[String])] =
        side match
          case Some((hashes, existing)) =>
            Some((hashes, if existing.nonEmpty then existing else names))
          case None => None
      def take(args: List[Term]): Unit =
        if args.length >= 2 && stateSide.isEmpty then
          val stateNames = if args.length >= 3 then namesOf(args(2)) else Nil
          val eventNames = if args.length >= 4 then namesOf(args(3)) else Nil
          stateSide = named(sideFromArg(args(0)), stateNames)
          eventSide = named(sideFromArg(args(1)), eventNames)
      val terminals = Set("goto", "stay", "stop", "computeGoto", "computeStay")
      object Walk extends TreeAccumulator[Unit]:
        def foldTree(u: Unit, tree: Tree)(owner: Symbol): Unit =
          if stateSide.isEmpty then
            tree match
              case Apply(TypeApply(Select(New(tpt), "<init>"), _), args) if tpt.show.contains("ViaBuilder") =>
                take(args)
              case Apply(Select(New(tpt), "<init>"), args) if tpt.show.contains("ViaBuilder") =>
                take(args)
              case Apply(TypeApply(Select(_, method), _), args) if args.length >= 2 && terminals.contains(method) =>
                take(args)
              case Apply(Select(_, method), args) if args.length >= 2 && terminals.contains(method) =>
                take(args)
              case _ =>
                foldOverTree((), tree)(owner)
          else ()
      end Walk
      Walk.foldTree((), term)(Symbol.spliceOwner)
      (stateSide, eventSide)
    end viaBuilderSides

    val builtSides = viaBuilderSides

    def fillSide(
        hashes: Set[Int],
        names: List[String],
        side: Option[(Set[Int], List[String])],
    ): (Set[Int], List[String]) =
      val poisoned = names.contains("<init>") || names.contains("?")
      side match
        case Some((denotedHashes, denotedNames)) =>
          val keptHashes =
            if hashes.nonEmpty && !poisoned then hashes
            else if denotedHashes.nonEmpty then denotedHashes
            else hashes
          val keptNames =
            if names.nonEmpty && !poisoned then names
            else if denotedNames.nonEmpty then denotedNames
            else names
          (keptHashes, keptNames)
        case None => (hashes, names)
      end match
    end fillSide

    def enrich(info: SpecHashInfo): SpecHashInfo =
      val state       = fillSide(info.stateHashes, info.stateNames, builtSides._1)
      val event       = fillSide(info.eventHashes, info.eventNames, builtSides._2)
      val stateFilled =
        if state._1.nonEmpty then state
        else fillSide(state._1, state._2, if matcherHashes.nonEmpty then Some((matcherHashes, matcherNames)) else None)
      val target =
        if info.targetDesc == "?" || info.targetDesc == "-> ?" || info.targetDesc.contains("<none>") then
          if denotedTarget != "?" && denotedTarget != "-> ?" && !denotedTarget.contains("<none>") then denotedTarget
          else info.targetDesc
        else info.targetDesc
      info.copy(
        stateHashes = stateFilled._1,
        stateNames = stateFilled._2,
        eventHashes = event._1,
        eventNames = event._2,
        targetDesc = target,
      )
    end enrich

    // First try to extract from DSL pattern (unexpanded inline macro)
    extractHashInfoFromDSL(term) match
      case Some(info) => return Some(enrich(info))
      case None       => ()

    // Helper to extract names from ViaBuilder constructor in a tree
    def extractViaBuilderNames(t: Term): Option[(List[String], List[String])] =
      var result: Option[(List[String], List[String])] = None
      object ViaBuilderFinder extends TreeAccumulator[Unit]:
        def foldTree(u: Unit, tree: Tree)(owner: Symbol): Unit =
          tree match
            case Apply(TypeApply(Select(New(tpt), "<init>"), _), args)
                if args.length >= 4 && tpt.show.contains("ViaBuilder") =>
              val stateNames = extractListStrings(args(2))
              val eventNames = extractListStrings(args(3))
              if stateNames.nonEmpty || eventNames.nonEmpty then result = Some((stateNames, eventNames))
              foldOverTree((), tree)(owner)
            case Apply(Select(New(tpt), "<init>"), args) if args.length >= 4 && tpt.show.contains("ViaBuilder") =>
              val stateNames = extractListStrings(args(2))
              val eventNames = extractListStrings(args(3))
              if stateNames.nonEmpty || eventNames.nonEmpty then result = Some((stateNames, eventNames))
              foldOverTree((), tree)(owner)
            case _ => foldOverTree((), tree)(owner)
      end ViaBuilderFinder
      ViaBuilderFinder.foldTree((), t)(Symbol.spliceOwner)
      result
    end extractViaBuilderNames

    // Pre-collect ViaBuilder names from the entire term tree
    val viaBuilderInfo = extractViaBuilderNames(term)

    object HashFinder extends TreeAccumulator[Option[SpecHashInfo]]:
      def foldTree(found: Option[SpecHashInfo], tree: Tree)(owner: Symbol): Option[SpecHashInfo] =
        found.orElse {
          tree match
            // Match TransitionSpec.goto/stay/stop calls - try this first
            case Apply(Select(_, methodName), args)
                if methodName == "goto" || methodName == "stay" || methodName == "stop" ||
                  methodName == "computeGoto" || methodName == "computeStay" =>
              if args.length >= 4 then
                val filled      = withMatcherLeaves(resolveInts(args(0)), extractListStrings(args(2)))
                val stateHashes = filled._1
                val eventHashes = resolveInts(args(1))
                var stateNames  = filled._2
                var eventNames  = extractListStrings(args(3))
                // If names are empty, use ViaBuilder info if available
                if stateNames.isEmpty && eventNames.isEmpty then
                  viaBuilderInfo.foreach { case (sn, en) =>
                    stateNames = sn
                    eventNames = en
                  }
                val targetDesc = methodName match
                  case "goto"        => if args.length >= 5 then s"-> ${extractTargetName(args(4))}" else "-> ?"
                  case "computeGoto" => if args.length >= 6 then s"-> ${extractTargetName(args(5))}" else "-> ?"
                  case "stay" | "computeStay" => "stay"
                  case "stop"                 => "stop"
                  case _                      => "?"
                Some(SpecHashInfo(stateHashes, eventHashes, stateNames, eventNames, targetDesc, false, "?"))
              else foldOverTree(None, tree)(owner)

            // Match @@ extension method call
            case Apply(Select(base, "@@"), List(aspectArg)) =>
              val isOverride = aspectArg.show.contains("overriding")
              foldTree(None, base)(owner).map(_.copy(isOverride = isOverride))

            // Match new ViaBuilder(...) constructor
            case Apply(TypeApply(Select(New(tpt), "<init>"), _), args)
                if args.length >= 4 && tpt.show.contains("ViaBuilder") =>
              val filled      = withMatcherLeaves(resolveInts(args(0)), extractListStrings(args(2)))
              val stateHashes = filled._1
              val eventHashes = resolveInts(args(1))
              val stateNames  = filled._2
              val eventNames  = extractListStrings(args(3))
              Some(SpecHashInfo(stateHashes, eventHashes, stateNames, eventNames, "?", false, "?"))

            // Match TransitionSpec.goto/stay/stop calls
            case Apply(Select(qual, methodName), args) if qual.show.contains("TransitionSpec") && args.length >= 4 =>
              val stateHashes = extractSetInts(args(0))
              val eventHashes = extractSetInts(args(1))
              val stateNames  = extractListStrings(args(2))
              val eventNames  = extractListStrings(args(3))
              val targetDesc  = methodName match
                case "goto"                 => if args.length >= 5 then s"-> ${extractTargetName(args(4))}" else "-> ?"
                case "computeGoto"          => if args.length >= 6 then s"-> ${extractTargetName(args(5))}" else "-> ?"
                case "stay" | "computeStay" => "stay"
                case "stop"                 => "stop"
                case _                      => "?"
              Some(SpecHashInfo(stateHashes, eventHashes, stateNames, eventNames, targetDesc, false, "?"))

            // Match TransitionSpec constructor: TransitionSpec.apply(stateHashes, eventHashes, stateNames, eventNames, targetDesc, ...)
            case Apply(Apply(TypeApply(Select(Ident("TransitionSpec"), "apply"), _), args), _) if args.length >= 5 =>
              val stateHashes = extractSetInts(args(0))
              val eventHashes = extractSetInts(args(1))
              val stateNames  = extractListStrings(args(2))
              val eventNames  = extractListStrings(args(3))
              val targetDesc  = args(4) match
                case Literal(StringConstant(s)) => s
                case _                          => "?"
              Some(SpecHashInfo(stateHashes, eventHashes, stateNames, eventNames, targetDesc, false, "?"))

            // Match new TransitionSpec(...)
            case Apply(Select(New(_), "<init>"), args) if args.length >= 5 =>
              val stateHashes = extractSetInts(args(0))
              val eventHashes = extractSetInts(args(1))
              val stateNames  = extractListStrings(args(2))
              val eventNames  = extractListStrings(args(3))
              val targetDesc  = args(4) match
                case Literal(StringConstant(s)) => s
                case _                          => "?"
              Some(SpecHashInfo(stateHashes, eventHashes, stateNames, eventNames, targetDesc, false, "?"))

            // Match spec.copy for override detection
            case Apply(Select(inner, "copy"), args) =>
              val innerInfo = foldTree(None, inner)(owner)
              innerInfo.map { info =>
                val hasOverride = args.exists {
                  case NamedArg("isOverride", Literal(BooleanConstant(true))) => true
                  case _                                                      => false
                }
                if hasOverride then info.copy(isOverride = true) else info
              }

            case _ => foldOverTree(None, tree)(owner)
        }
    end HashFinder

    HashFinder.foldTree(None, term)(Symbol.spliceOwner).map(enrich)
  end extractHashInfo

  // ====== Expression Generation Helpers ======

  /** Generate IncludedHashInfo expression from SpecHashInfo.
    *
    * This creates a literal expression that can be embedded in generated code for compile-time extraction.
    */
  def hashInfoToExpr(using Quotes)(info: SpecHashInfo): Expr[IncludedHashInfo] =
    val stateHashesExpr = Expr(info.stateHashes)
    val eventHashesExpr = Expr(info.eventHashes)
    val stateNamesExpr  = Expr(info.stateNames)
    val eventNamesExpr  = Expr(info.eventNames)
    val targetDescExpr  = Expr(info.targetDesc)
    val isOverrideExpr  = Expr(info.isOverride)
    '{
      IncludedHashInfo(
        $stateHashesExpr,
        $eventHashesExpr,
        $stateNamesExpr,
        $eventNamesExpr,
        $targetDescExpr,
        $isOverrideExpr,
      )
    }
  end hashInfoToExpr

  /** Generate OrphanInfo expression from SpecHashInfo.
    *
    * Used to embed orphan override info in generated Assembly for Machine.apply to warn about.
    */
  def orphanInfoToExpr(using Quotes)(info: SpecHashInfo): Expr[OrphanInfo] =
    val stateHashesExpr = Expr(info.stateHashes)
    val eventHashesExpr = Expr(info.eventHashes)
    val stateNamesExpr  = Expr(info.stateNames)
    val eventNamesExpr  = Expr(info.eventNames)
    '{ OrphanInfo($stateHashesExpr, $eventHashesExpr, $stateNamesExpr, $eventNamesExpr) }

  /** Build source description from SpecHashInfo.
    *
    * Returns a human-readable description like "StateA,StateB via Event1" or falls back to "spec #N".
    */
  def sourceDescFromInfo(info: SpecHashInfo, fallbackIdx: Int): String =
    DuplicateDetection.sourceDescFromNames(info.stateNames, info.eventNames, fallbackIdx)

  /** Extract SpecHashInfo from a term with index, handling override detection.
    *
    * This is the shared logic used by both `assemblyImpl` and `assemblyAllImpl`.
    */
  def getSpecInfo(using Quotes)(term: quotes.reflect.Term, idx: Int): SpecHashInfo =
    val hasOverrideAtCallSite = term.show.contains("overriding")
    extractHashInfo(term) match
      case Some(h) =>
        h.copy(
          isOverride = h.isOverride || hasOverrideAtCallSite,
          sourceDesc = sourceDescFromInfo(h, idx),
        )
      case None =>
        SpecHashInfo(Set.empty, Set.empty, Nil, Nil, "?", hasOverrideAtCallSite, s"spec #${idx + 1}")
  end getSpecInfo

  /** Compute orphan override expressions from spec infos.
    *
    * Orphan overrides are specs marked with `@@ Aspect.overriding` that don't actually override anything.
    */
  def computeOrphanExprs(using Quotes)(specInfos: List[(SpecHashInfo, Int)]): List[Expr[OrphanInfo]] =
    // Convert to pure data structure
    val keys = specInfos.map { case (info, idx) =>
      (
        DuplicateDetection.TransitionKey(
          stateHashes = info.stateHashes,
          eventHashes = info.eventHashes,
          stateNames = info.stateNames,
          eventNames = info.eventNames,
          targetDesc = info.targetDesc,
          isOverride = info.isOverride,
          sourceDesc = info.sourceDesc,
        ),
        idx,
      )
    }

    // Find orphans using pure logic
    val orphanKeys = DuplicateDetection.findOrphanOverrides(keys)

    // Convert back to expressions
    orphanKeys.map { key =>
      val info = SpecHashInfo(
        stateHashes = key.stateHashes,
        eventHashes = key.eventHashes,
        stateNames = key.stateNames,
        eventNames = key.eventNames,
        targetDesc = key.targetDesc,
        isOverride = key.isOverride,
        sourceDesc = key.sourceDesc,
      )
      orphanInfoToExpr(info)
    }
  end computeOrphanExprs

  // ====== Symbol-based Duplicate Detection ======

  /** Extract producingAncestorHashes from a TransitionSpec term.
    *
    * Walks the AST to find `producingAncestorHashes = Some(Set(...))` patterns, which are embedded by the `producing`
    * macro. Returns None if no producing effect is configured, or Some(Set[Int]) with the ancestor hashes.
    */
  def extractProducingAncestorHashes(using Quotes)(term: quotes.reflect.Term): Option[Set[Int]] =
    import quotes.reflect.*
    var result: Option[Set[Int]] = None

    object HashFinder extends TreeAccumulator[Unit]:
      def foldTree(u: Unit, tree: Tree)(owner: Symbol): Unit =
        if result.isEmpty then
          tree match
            // Match NamedArg pattern: producingAncestorHashes = Some(Set(...))
            case NamedArg("producingAncestorHashes", someArg) =>
              someArg match
                // Some(Set(...)) pattern
                case Apply(TypeApply(Select(_, "apply"), _), List(setArg)) =>
                  val hashes = extractSetInts(setArg)
                  if hashes.nonEmpty then result = Some(hashes)
                case Apply(Select(_, "apply"), List(setArg)) =>
                  val hashes = extractSetInts(setArg)
                  if hashes.nonEmpty then result = Some(hashes)
                case Apply(_, List(setArg)) if someArg.show.contains("Some") =>
                  val hashes = extractSetInts(setArg)
                  if hashes.nonEmpty then result = Some(hashes)
                case _ =>
                  foldOverTree((), tree)(owner)
            // Also check for copy() calls with producingAncestorHashes
            case Apply(Select(_, "copy"), args) =>
              args.foreach { arg =>
                arg match
                  case NamedArg("producingAncestorHashes", someArg) =>
                    someArg match
                      case Apply(TypeApply(Select(_, "apply"), _), List(setArg)) =>
                        val hashes = extractSetInts(setArg)
                        if hashes.nonEmpty then result = Some(hashes)
                      case Apply(Select(_, "apply"), List(setArg)) =>
                        val hashes = extractSetInts(setArg)
                        if hashes.nonEmpty then result = Some(hashes)
                      case Apply(_, List(setArg)) if someArg.show.contains("Some") =>
                        val hashes = extractSetInts(setArg)
                        if hashes.nonEmpty then result = Some(hashes)
                      case _ => ()
                  case _ => ()
              }
              foldOverTree((), tree)(owner)
            case _ =>
              foldOverTree((), tree)(owner)
    end HashFinder

    HashFinder.foldTree((), term)(Symbol.spliceOwner)
    result
  end extractProducingAncestorHashes

  /** Check if a term is the @@ method (used in extension method pattern matching). */
  def isAtAtMethod(using Quotes)(t: quotes.reflect.Term): Boolean =
    import quotes.reflect.*
    t match
      case Ident(name)         => name == "@@"
      case Select(_, "@@")     => true
      case TypeApply(inner, _) => isAtAtMethod(inner)
      case Apply(inner, _)     => isAtAtMethod(inner)
      case _                   => false

  /** Get the base symbol from a term (handling @@ extension method patterns). */
  def getBaseSymbol(using Quotes)(term: quotes.reflect.Term): Option[quotes.reflect.Symbol] =
    import quotes.reflect.*
    term match
      // Extension method pattern: Apply(Apply(TypeApply(Ident(@@), _), [specArg]), [aspectArg])
      case Apply(Apply(inner, specArgs), _) if isAtAtMethod(inner) && specArgs.nonEmpty =>
        specArgs.head match
          case Ident(_) => Some(specArgs.head.symbol)
          case _        => getBaseSymbol(specArgs.head)
      // Single Apply pattern: Apply(Select(base, "@@"), _)
      case Apply(Select(base, "@@"), _) => getBaseSymbol(base)
      // Plain val reference
      case Ident(_) => Some(term.symbol)
      case _        => None
    end match
  end getBaseSymbol

  /** Check if a term has override at the call site (e.g., `t1 @@ Aspect.overriding`). */
  def hasOverrideAtCallSite(using Quotes)(term: quotes.reflect.Term): Boolean =
    import quotes.reflect.*
    term match
      // Handle wrapper nodes first
      case Inlined(_, _, inner) => hasOverrideAtCallSite(inner)
      case Typed(inner, _)      => hasOverrideAtCallSite(inner)
      case Block(_, expr)       => hasOverrideAtCallSite(expr)
      // Extension method pattern with double Apply
      case Apply(Apply(inner, _), aspectArgs) if isAtAtMethod(inner) && aspectArgs.nonEmpty =>
        aspectArgs.head.show.contains("overriding")
      // Single Apply pattern
      case Apply(Select(_, "@@"), List(aspectArg)) =>
        aspectArg.show.contains("overriding")
      case _ => false
    end match
  end hasOverrideAtCallSite

  // ====== Duplicate Detection Logic ======

  /** Result of duplicate detection - contains override info messages or triggers error. */
  case class DuplicateCheckResult(
      overrideInfos: List[String],
      symbolOverrideInfos: List[String],
  )

  /** Perform hash-based duplicate detection on a list of specs.
    *
    * This is a thin wrapper around the pure `DuplicateDetection.detectDuplicates` that handles compilation error
    * reporting.
    *
    * @param specInfos
    *   List of (SpecHashInfo, index) pairs
    * @param errorPrefix
    *   Prefix for error messages (e.g., "Duplicate transition" or "Duplicate transition in assembly")
    * @return
    *   DuplicateCheckResult with override info, or aborts compilation on error
    */
  def checkDuplicates(using
      Quotes
  )(
      specInfos: List[(SpecHashInfo, Int)],
      errorPrefix: String,
  ): DuplicateCheckResult =
    import quotes.reflect.*

    // Convert to pure data structure
    val keys = specInfos.map { case (info, idx) =>
      (
        DuplicateDetection.TransitionKey(
          stateHashes = info.stateHashes,
          eventHashes = info.eventHashes,
          stateNames = info.stateNames,
          eventNames = info.eventNames,
          targetDesc = info.targetDesc,
          isOverride = info.isOverride,
          sourceDesc = info.sourceDesc,
        ),
        idx,
      )
    }

    // Run pure duplicate detection
    val result = DuplicateDetection.detectDuplicates(keys)

    // Handle result
    result.error match
      case Some(err) =>
        report.errorAndAbort(err.message(errorPrefix))
      case None =>
        val overrideInfos = result.overrideInfos.map(_.message)
        DuplicateCheckResult(overrideInfos, Nil)
  end checkDuplicates

end MacroUtils
