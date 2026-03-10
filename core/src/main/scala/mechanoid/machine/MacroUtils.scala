package mechanoid.machine

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

    // Compute hash from a symbol's full name
    def computeSymbolHash(sym: Symbol): Int =
      sym.fullName.hashCode

    // Extract symbol from a term that represents a state or event value
    def extractValueSymbol(t: Term): Option[Symbol] =
      t match
        case Ident(_)             => Some(t.symbol)
        case Select(_, _)         => Some(t.symbol)
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

  /** Extract hash info from a TransitionSpec term (either inline expr or val definition).
    *
    * Uses a TreeAccumulator to find ViaBuilder, TransitionSpec, AllMatcher constructors and extract their compile-time
    * hash values. Also handles unexpanded DSL patterns like `A via E1 to B`.
    */
  def extractHashInfo(using Quotes)(term: quotes.reflect.Term): Option[SpecHashInfo] =
    import quotes.reflect.*

    // First try to extract from DSL pattern (unexpanded inline macro)
    extractHashInfoFromDSL(term) match
      case Some(info) => return Some(info)
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
                if methodName == "goto" || methodName == "stay" || methodName == "stop" =>
              if args.length >= 4 then
                val stateHashes = extractSetInts(args(0))
                val eventHashes = extractSetInts(args(1))
                var stateNames  = extractListStrings(args(2))
                var eventNames  = extractListStrings(args(3))
                // If names are empty, use ViaBuilder info if available
                if stateNames.isEmpty && eventNames.isEmpty then
                  viaBuilderInfo.foreach { case (sn, en) =>
                    stateNames = sn
                    eventNames = en
                  }
                val targetDesc = methodName match
                  case "goto" => if args.length >= 5 then s"-> ${extractTargetName(args(4))}" else "-> ?"
                  case "stay" => "stay"
                  case "stop" => "stop"
                  case _      => "?"
                Some(SpecHashInfo(stateHashes, eventHashes, stateNames, eventNames, targetDesc, false, "?"))
              else foldOverTree(None, tree)(owner)

            // Match @@ extension method call
            case Apply(Select(base, "@@"), List(aspectArg)) =>
              val isOverride = aspectArg.show.contains("overriding")
              foldTree(None, base)(owner).map(_.copy(isOverride = isOverride))

            // Match new ViaBuilder(...) constructor
            case Apply(TypeApply(Select(New(tpt), "<init>"), _), args)
                if args.length >= 4 && tpt.show.contains("ViaBuilder") =>
              val stateHashes = extractSetInts(args(0))
              val eventHashes = extractSetInts(args(1))
              val stateNames  = extractListStrings(args(2))
              val eventNames  = extractListStrings(args(3))
              Some(SpecHashInfo(stateHashes, eventHashes, stateNames, eventNames, "?", false, "?"))

            // Match TransitionSpec.goto/stay/stop calls
            case Apply(Select(qual, methodName), args) if qual.show.contains("TransitionSpec") && args.length >= 4 =>
              val stateHashes = extractSetInts(args(0))
              val eventHashes = extractSetInts(args(1))
              val stateNames  = extractListStrings(args(2))
              val eventNames  = extractListStrings(args(3))
              val targetDesc  = methodName match
                case "goto" => if args.length >= 5 then s"-> ${extractTargetName(args(4))}" else "-> ?"
                case "stay" => "stay"
                case "stop" => "stop"
                case _      => "?"
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

            // Match new AllMatcher(...) constructor
            case Apply(TypeApply(Select(New(tpt), "<init>"), _), args)
                if args.length >= 2 && tpt.show.contains("AllMatcher") =>
              val stateHashes = extractSetInts(args(0))
              val stateNames  = extractListStrings(args(1))
              Some(SpecHashInfo(stateHashes, Set.empty, stateNames, Nil, "?", false, "?"))

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

    HashFinder.foldTree(None, term)(Symbol.spliceOwner)
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
