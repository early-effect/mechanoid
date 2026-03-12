package mechanoid.machine

import scala.quoted.*

/** Assembly-related macro implementations.
  *
  * Contains the implementations for `assembly`, `assemblyAll`, and `combine`/`++` macros.
  */
private[machine] object AssemblyMacros:

  /** Implementation of `assembly` macro - creates a compile-time composable collection of specs.
    *
    * This macro:
    *   1. Accepts TransitionSpec arguments
    *   2. Performs compile-time duplicate detection (within this assembly)
    *   3. Infers command type from all specs
    *   4. Generates a literal `Assembly.apply(List(...))` expression for compile-time visibility
    */
  def assemblyImpl[S: Type, E: Type](
      first: Expr[TransitionSpec[S, E, ?]],
      rest: Expr[Seq[TransitionSpec[S, E, ?]]],
  )(using Quotes): Expr[Assembly[S, E]] =
    import quotes.reflect.*

    // Extract individual arg expressions - first is guaranteed, rest may be empty
    val restTerms: List[Term] = rest match
      case Varargs(exprs) => exprs.toList.map(_.asTerm)
      case other          =>
        report.errorAndAbort(s"Expected varargs, got: ${other.show}")

    val argTerms: List[Term] = first.asTerm :: restTerms

    // Build allSpecInfos in SOURCE ORDER
    val allSpecInfos = argTerms.zipWithIndex.map { case (term, idx) =>
      (MacroUtils.getSpecInfo(term, idx), idx)
    }

    MacroUtils.checkDuplicates(allSpecInfos, "Duplicate transition in assembly")

    // LUB validation for producing effects
    val eventTypeHash    = TypeRepr.of[E].typeSymbol.fullName.hashCode
    val eventAncestors   = ProducingMacros.getSealedAncestorHashes[E]
    val validEventHashes = eventAncestors + eventTypeHash

    // Validate each spec's producing effect has a common ancestor with E
    argTerms.zipWithIndex.foreach { case (term, idx) =>
      MacroUtils.extractProducingAncestorHashes(term) match
        case Some(producedAncestors) =>
          val commonAncestors = producedAncestors.intersect(validEventHashes)
          if commonAncestors.isEmpty then
            report.errorAndAbort(
              s"""Type mismatch in producing effect at spec #${idx + 1}!
                 |
                 |The produced event type does not share a common sealed ancestor with the FSM's event type.
                 |The produced event must be a case within the same sealed hierarchy as the FSM's event type ${TypeRepr
                  .of[E]
                  .typeSymbol
                  .name}.""".stripMargin,
              term.pos,
            )
          end if
        case None => () // No producing effect on this spec
    }

    // Code generation - include hash info as literal for compile-time extraction by combine/++
    val specExprs: List[Expr[TransitionSpec[S, E, ?]]] = argTerms.map { term =>
      term.asExpr.asInstanceOf[Expr[TransitionSpec[S, E, ?]]]
    }
    val specsExpr = Expr.ofList(specExprs)

    // Generate literal hash info for compile-time extraction by combine/++
    val allHashInfoExprs = allSpecInfos.map { case (info, _) => MacroUtils.hashInfoToExpr(info) }
    val hashInfosExpr    = Expr.ofList(allHashInfoExprs)

    // Compute orphan overrides using shared helper
    val orphanInfoExprs = MacroUtils.computeOrphanExprs(allSpecInfos)
    val orphansExpr     = '{ Set(${ Varargs(orphanInfoExprs) }*) }

    '{
      Assembly.apply[S, E](
        $specsExpr,
        $hashInfosExpr,
        $orphansExpr,
      )
    }
  end assemblyImpl

  /** Implementation of `combine`/`++` macro - combines two assemblies with compile-time duplicate detection.
    *
    * Extracts hash infos from both assemblies, runs checkDuplicates, and produces a flattened Assembly.
    */
  def combineImpl[S: Type, E: Type](
      selfExpr: Expr[Assembly[S, E]],
      otherExpr: Expr[Assembly[S, E]],
  )(using Quotes): Expr[Assembly[S, E]] =
    import quotes.reflect.*

    // Extract hashInfos from an Assembly expression's AST
    def extractHashInfos(term: Term): List[MacroUtils.SpecHashInfo] =
      term match
        case Apply(TypeApply(Select(_, "apply"), _), args) if args.length >= 2 =>
          MacroUtils.extractListElements(args(1)).flatMap(extractSingleHashInfoFromTerm)
        case Apply(Select(_, "apply"), args) if args.length >= 2 =>
          MacroUtils.extractListElements(args(1)).flatMap(extractSingleHashInfoFromTerm)
        case Apply(Select(New(tpt), "<init>"), args) if tpt.show.contains("Assembly") && args.length >= 2 =>
          MacroUtils.extractListElements(args(1)).flatMap(extractSingleHashInfoFromTerm)
        case Inlined(_, _, inner) => extractHashInfos(inner)
        case Block(_, expr)       => extractHashInfos(expr)
        case Typed(inner, _)      => extractHashInfos(inner)
        // Follow through .onEnter(...) / .onExit(...) chains
        case t if peelMethodCalls(t).isDefined => extractHashInfos(peelMethodCalls(t).get)
        // Follow val/def references
        case Ident(_) if term.symbol.exists =>
          try
            term.symbol.tree match
              case ValDef(_, _, Some(rhs))        => extractHashInfos(rhs)
              case dd: DefDef if dd.rhs.isDefined => extractHashInfos(dd.rhs.get)
              case _                              => Nil
          catch case _: Exception => Nil
        case _ => Nil

    def peelMethodCalls(term: Term): Option[Term] =
      term match
        case Apply(inner, _) =>
          inner match
            case Select(receiver, name) if name != "apply" && name != "<init>"               => Some(receiver)
            case TypeApply(Select(receiver, name), _) if name != "apply" && name != "<init>" =>
              Some(receiver)
            case _ => peelMethodCalls(inner)
        case _ => None

    def extractSingleHashInfoFromTerm(term: Term): Option[MacroUtils.SpecHashInfo] =
      def extractFromArgs(args: List[Term]): Option[MacroUtils.SpecHashInfo] =
        val stateHashes = MacroUtils.extractSetInts(args(0))
        val eventHashes = MacroUtils.extractSetInts(args(1))
        val stateNames  = MacroUtils.extractListStrings(args(2))
        val eventNames  = MacroUtils.extractListStrings(args(3))
        val targetDesc  = MacroUtils.unwrap(args(4)) match
          case Literal(StringConstant(s)) => s
          case _                          => "?"
        val isOverride = MacroUtils.extractBoolean(args(5))
        if stateHashes.nonEmpty || eventHashes.nonEmpty then
          Some(MacroUtils.SpecHashInfo(stateHashes, eventHashes, stateNames, eventNames, targetDesc, isOverride, "?"))
        else None
      end extractFromArgs

      term match
        case Apply(_, args) if args.length >= 6 => extractFromArgs(args)
        case Inlined(_, _, inner)               => extractSingleHashInfoFromTerm(inner)
        // Handle SeqLiteral - varargs container
        case other if other.getClass.getName.contains("SeqLiteral") =>
          var result: Option[MacroUtils.SpecHashInfo] = None
          object ApplyFinder extends TreeAccumulator[Unit]:
            def foldTree(u: Unit, tree: Tree)(owner: Symbol): Unit =
              if result.isEmpty then
                tree match
                  case Apply(fn, args) if args.length >= 6 && fn.show.contains("IncludedHashInfo") =>
                    result = extractFromArgs(args)
                  case _ => foldOverTree((), tree)(owner)
          ApplyFinder.foldTree((), other)(Symbol.spliceOwner)
          result
        case _ => None
      end match
    end extractSingleHashInfoFromTerm

    val selfInfos  = extractHashInfos(selfExpr.asTerm)
    val otherInfos = extractHashInfos(otherExpr.asTerm)

    if selfInfos.isEmpty then
      report.errorAndAbort(
        """Cannot extract assembly info for compile-time duplicate detection in combine().
          |Both assemblies must be inline expressions or inline def references.
          |
          |Use: combine(assembly[S, E](...), assembly[S, E](...))""".stripMargin
      )

    if otherInfos.isEmpty then
      report.errorAndAbort(
        """Cannot extract assembly info for compile-time duplicate detection in ++.
          |Both assemblies must be inline expressions or inline def references.
          |
          |Use: assembly[S, E](...) ++ assembly[S, E](...)""".stripMargin
      )

    // Combine and check for duplicates
    var idx          = 0
    val allSpecInfos = (selfInfos ++ otherInfos).map { info =>
      val result = (info.copy(sourceDesc = MacroUtils.sourceDescFromInfo(info, idx)), idx)
      idx += 1
      result
    }

    MacroUtils.checkDuplicates(allSpecInfos, "Duplicate transition in assembly combination (++)")

    // Compute orphan overrides
    val orphanInfoExprs = MacroUtils.computeOrphanExprs(allSpecInfos)
    val orphansExpr     = '{ Set(${ Varargs(orphanInfoExprs) }*) }

    // Generate combined hash infos
    val allHashInfoExprs = allSpecInfos.map { case (info, _) => MacroUtils.hashInfoToExpr(info) }
    val hashInfosExpr    = Expr.ofList(allHashInfoExprs)

    '{
      Assembly.apply[S, E](
        $selfExpr.specs ++ $otherExpr.specs,
        $hashInfosExpr,
        $orphansExpr,
        $selfExpr.stateEntryEffects ++ $otherExpr.stateEntryEffects,
        $selfExpr.stateExitEffects ++ $otherExpr.stateExitEffects,
      )
    }
  end combineImpl

  /** Implementation of `assemblyAll` macro - block syntax for creating Assemblies. */
  def assemblyAllImpl[S: Type, E: Type](
      block: Expr[Any]
  )(using Quotes): Expr[Assembly[S, E]] =
    import quotes.reflect.*

    val term = block.asTerm

    // Use shared type checkers from MacroUtils
    def isTransitionSpec(term: Term): Boolean = MacroUtils.isTransitionSpecType(term.tpe)

    def isHelperValDef(stmt: Statement): Boolean = stmt match
      case ValDef(_, _, Some(rhs)) => !isTransitionSpec(rhs)
      case _                       => false

    case class BlockContents(
        helperVals: List[Statement],
        specTerms: List[Term],
    )

    def processBlock(t: Term): BlockContents = t match
      case Block(statements, finalExpr) =>
        val helperVals = statements.filter(isHelperValDef)

        val specTerms = statements.flatMap {
          case term: Term if isTransitionSpec(term)             => List(term)
          case ValDef(_, _, Some(rhs)) if isTransitionSpec(rhs) =>
            List(
              Ref(Symbol.requiredModule("scala.Predef"))
                .select(Symbol.requiredMethod("identity"))
                .appliedToType(rhs.tpe)
                .appliedTo(rhs)
            )
          case _ => Nil
        }

        val finalSpecs = if isTransitionSpec(finalExpr) then List(finalExpr) else Nil

        BlockContents(helperVals, specTerms ++ finalSpecs)

      case Inlined(_, bindings, inner) =>
        val BlockContents(innerVals, innerSpecs) = processBlock(inner)
        BlockContents(bindings.toList ++ innerVals, innerSpecs)

      case other if isTransitionSpec(other) =>
        BlockContents(Nil, List(other))

      case _ => BlockContents(Nil, Nil)

    val BlockContents(helperVals, specTerms) = processBlock(term)

    if specTerms.isEmpty then
      report.errorAndAbort(
        "No TransitionSpec expressions found in block. " +
          "Each line should be a transition (State via Event to Target)."
      )

    // Hash-based duplicate detection using shared helper
    val specInfos = specTerms.zipWithIndex.map { case (term, idx) =>
      (MacroUtils.getSpecInfo(term, idx), idx)
    }

    MacroUtils.checkDuplicates(specInfos, "Duplicate transition in assemblyAll")

    // LUB validation for producing effects
    val eventTypeHash    = TypeRepr.of[E].typeSymbol.fullName.hashCode
    val eventAncestors   = ProducingMacros.getSealedAncestorHashes[E]
    val validEventHashes = eventAncestors + eventTypeHash

    // Validate each spec's producing effect has a common ancestor with E
    specTerms.zipWithIndex.foreach { case (term, idx) =>
      MacroUtils.extractProducingAncestorHashes(term) match
        case Some(producedAncestors) =>
          val commonAncestors = producedAncestors.intersect(validEventHashes)
          if commonAncestors.isEmpty then
            report.errorAndAbort(
              s"""Type mismatch in producing effect at spec #${idx + 1}!
                 |
                 |The produced event type does not share a common sealed ancestor with the FSM's event type.
                 |The produced event must be a case within the same sealed hierarchy as the FSM's event type ${TypeRepr
                  .of[E]
                  .typeSymbol
                  .name}.""".stripMargin,
              term.pos,
            )
          end if
        case None => () // No producing effect on this spec
    }

    // Code generation
    val specExprs: List[Expr[TransitionSpec[S, E, ?]]] = specTerms.map { term =>
      term.asExpr.asInstanceOf[Expr[TransitionSpec[S, E, ?]]]
    }
    val specsExpr = Expr.ofList(specExprs)

    // Generate literal hash info for compile-time extraction by combine/++
    val allHashInfoExprs = specInfos.map { case (info, _) => MacroUtils.hashInfoToExpr(info) }
    val hashInfosExpr    = Expr.ofList(allHashInfoExprs)

    // Compute orphan overrides using shared helper
    val orphanInfoExprs = MacroUtils.computeOrphanExprs(specInfos)
    val orphansExpr     = '{ Set(${ Varargs(orphanInfoExprs) }*) }

    val assemblyExpr = '{
      Assembly.apply[S, E](
        $specsExpr,
        $hashInfosExpr,
        $orphansExpr,
      )
    }

    if helperVals.nonEmpty then
      val assemblyTerm = assemblyExpr.asTerm
      Block(helperVals.toList, assemblyTerm).asExprOf[Assembly[S, E]]
    else assemblyExpr
  end assemblyAllImpl

end AssemblyMacros
