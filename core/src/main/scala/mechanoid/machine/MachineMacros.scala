package mechanoid.machine

import scala.quoted.*
import mechanoid.core.Finite

/** Machine-related macro implementations.
  *
  * Contains the implementation for `Machine.apply` which checks for orphan overrides and emits compile-time warnings.
  */
private[machine] object MachineMacros:

  /** Implementation of `Machine.apply` macro.
    *
    * This macro:
    *   1. Extracts orphanOverrides from the Assembly
    *   2. Emits compile-time warnings for any orphan overrides
    *   3. Generates the runtime `Machine.fromSpecs` call
    *
    * Note: Orphan detection works when the assembly is passed inline to Machine.apply, or when using `inline def`. When
    * the assembly is stored in a regular `val`, the orphan information cannot be extracted and a compile error is
    * emitted requiring inline usage.
    */
  def applyImpl[S: Type, E: Type](
      assemblyExpr: Expr[Assembly[S, E]],
      finiteS: Expr[Finite[S]],
      finiteE: Expr[Finite[E]],
  )(using Quotes): Expr[Machine[S, E]] =
    import quotes.reflect.*

    // Extract orphan overrides from the Assembly
    // Returns Some(orphans) if extraction succeeded, None if it failed
    extractOrphanOverrides(assemblyExpr) match
      case None =>
        // Failed to extract orphans - require inline usage
        report.errorAndAbort(
          """Assembly must be passed inline to Machine() for orphan override detection.
            |
            |Instead of:
            |  val asm = assembly[S, E](...)
            |  Machine(asm)
            |
            |Use:
            |  Machine(assembly[S, E](...))
            |
            |Or use 'inline def' to preserve the expression:
            |  inline def asm = assembly[S, E](...)
            |  Machine(asm)""".stripMargin
        )
      case Some(orphans) =>
        // Successfully extracted orphans - emit warnings for any remaining
        for orphan <- orphans do
          report.warning(
            s"${orphan.description}: marked @@ Aspect.overriding but no duplicate to override"
          )
        '{
          Machine.fromSpecs[S, E](
            $assemblyExpr.specs,
            $assemblyExpr.stateEntryEffects,
            $assemblyExpr.stateExitEffects,
          )(using $finiteS, $finiteE)
        }
    end match
  end applyImpl

  /** Data class for orphan override info extracted from AST. */
  private case class OrphanData(
      stateNames: List[String],
      eventNames: List[String],
  ):
    def description: String =
      if stateNames.nonEmpty && eventNames.nonEmpty then s"${stateNames.mkString(",")} via ${eventNames.mkString(",")}"
      else "Transition" // Fallback when names aren't available

  /** Extract OrphanInfo data from Assembly constructor at compile time.
    *
    * Looks for `Assembly.apply(specs, hashInfos, orphanOverrides)` pattern and extracts the orphan set.
    *
    * @return
    *   Some(orphans) if extraction succeeded (empty list means no orphans), None if extraction failed
    */
  private def extractOrphanOverrides(using
      Quotes
  )(assemblyExpr: Expr[Assembly[?, ?]]): Option[List[OrphanData]] =
    import quotes.reflect.*

    def extractFromTerm(term: Term): Option[List[OrphanData]] =
      term match
        // Assembly.apply(specs, hashInfos, orphanOverrides)
        case Apply(TypeApply(Select(_, "apply"), _), args) if args.length >= 3 =>
          Some(extractOrphanSet(args(2)))
        case Apply(Select(_, "apply"), args) if args.length >= 3 =>
          Some(extractOrphanSet(args(2)))
        // new Assembly(specs, hashInfos, orphanOverrides)
        case Apply(Select(New(tpt), "<init>"), args) if tpt.show.contains("Assembly") && args.length >= 3 =>
          Some(extractOrphanSet(args(2)))
        case Apply(TypeApply(Select(New(tpt), "<init>"), _), args)
            if tpt.show.contains("Assembly") && args.length >= 3 =>
          Some(extractOrphanSet(args(2)))
        case Inlined(_, _, inner) =>
          extractFromTerm(inner)
        case Block(_, expr) =>
          extractFromTerm(expr)
        case Typed(inner, _) =>
          extractFromTerm(inner)
        // Follow through method calls on Assembly (e.g. .onEnter(...), .onExit(...))
        // These may have multiple curried parameter lists producing nested Apply nodes.
        // Recursively peel Apply layers to find the Select(receiver, method) at the core.
        case t if peelApplies(t).isDefined =>
          extractFromTerm(peelApplies(t).get)
        // Fallback: try to follow val references if symbol exists
        // Note: For object/class member vals, the rhs is typically erased after type-checking,
        // so orphan detection only works reliably for inline vals.
        case _ =>
          if term.symbol.exists then
            try
              term.symbol.tree match
                case vd: ValDef =>
                  vd.rhs match
                    case Some(rhs) => extractFromTerm(rhs)
                    case None      => None // rhs erased - can't extract orphans
                case _ => None
            catch case _: Exception => None
          else None

    // Peel nested Apply layers to find a Select(receiver, methodName) at the core.
    // Returns Some(receiver) if a method call pattern is found, None otherwise.
    // Avoids matching Assembly.apply / new Assembly constructors (handled by earlier cases).
    def peelApplies(term: Term): Option[Term] =
      term match
        case Apply(inner, _) =>
          inner match
            case Select(receiver, name) if name != "apply" && name != "<init>" =>
              Some(receiver)
            case TypeApply(Select(receiver, name), _) if name != "apply" && name != "<init>" =>
              Some(receiver)
            case _ => peelApplies(inner)
        case _ => None

    extractFromTerm(assemblyExpr.asTerm)
  end extractOrphanOverrides

  /** Extract OrphanInfo instances from a Set expression. */
  private def extractOrphanSet(using Quotes)(setTerm: quotes.reflect.Term): List[OrphanData] =
    import quotes.reflect.*

    // Helper to extract string literals from a List
    def extractStrings(term: Term): List[String] =
      MacroUtils.extractListStrings(term)

    // Extract a single OrphanInfo from its constructor
    def extractSingleOrphan(term: Term): Option[OrphanData] =
      term match
        // OrphanInfo(stateHashes, eventHashes, stateNames, eventNames)
        case apply: Apply if apply.args.length >= 4 =>
          val stateNames = extractStrings(apply.args(2))
          val eventNames = extractStrings(apply.args(3))
          // Always return the orphan - even if names are empty, we still want to warn
          Some(OrphanData(stateNames, eventNames))
        case Inlined(_, _, inner) =>
          extractSingleOrphan(inner)
        case _ =>
          None

    // Check if a term represents a Set reference (Ident or Select ending in Set)
    def isSetRef(t: Term): Boolean =
      t match
        case Ident("Set")        => true
        case Select(_, "Set")    => true // scala.Predef.Set
        case TypeApply(inner, _) => isSetRef(inner)
        case _                   => false

    // Extract elements from Set constructor
    def extractSetElements(term: Term): List[Term] =
      term match
        // Set(elem1, elem2, ...) - handle various patterns
        case Apply(TypeApply(fn, _), args) if isSetRef(fn) || fn.show.contains("Set") =>
          args.flatMap(unwrapRepeated)
        case Apply(fn, args) if isSetRef(fn) || fn.show.contains("Set") =>
          args.flatMap(unwrapRepeated)
        // Set.empty
        case Select(base, "empty") if isSetRef(base) || base.show.contains("Set")               => Nil
        case TypeApply(Select(base, "empty"), _) if isSetRef(base) || base.show.contains("Set") => Nil
        case Inlined(_, _, inner)                                                               =>
          extractSetElements(inner)
        case Typed(inner, _) =>
          extractSetElements(inner)
        case _ =>
          Nil

    def unwrapRepeated(term: Term): List[Term] =
      term match
        case Repeated(elems, _)           => elems
        case Typed(Repeated(elems, _), _) => elems
        case Typed(inner, _)              => unwrapRepeated(inner)
        case Inlined(_, _, inner)         => unwrapRepeated(inner)
        case other                        => List(other)

    extractSetElements(setTerm).flatMap(extractSingleOrphan)
  end extractOrphanSet

end MachineMacros
