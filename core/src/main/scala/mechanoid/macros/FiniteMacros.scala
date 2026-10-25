package mechanoid.macros

import scala.quoted.*
import mechanoid.core.CaseHasher

/** Macro utilities for deriving Finite instances with fully qualified names and hash-based identification. */
object FiniteMacros:

  /** Case information extracted at compile time. */
  case class CaseInfo(simpleName: String, fullName: String, hash: Int)

  /** Hierarchy information mapping parent types to their leaf descendants.
    *
    * This enables `whenAny[ParentState]` to expand to transitions for all leaf states under that parent.
    */
  case class HierarchyInfo(parentToLeaves: Map[Int, Set[Int]])

  /** Extract case information for a sealed type at compile time.
    *
    * Returns an array of CaseInfo containing:
    *   - simpleName: The simple case name (e.g., "Idle")
    *   - fullName: The fully qualified name (e.g., "com.example.MyState.Idle")
    *   - hash: Computed at compile time
    *
    * Hash collisions are detected at compile time and result in a compilation error.
    *
    * @param hasher
    *   The CaseHasher to use (CaseHasher.Default or CaseHasher.Murmur3)
    */
  inline def extractCaseInfo[T](inline hasher: CaseHasher): Array[CaseInfo] =
    ${ extractCaseInfoImpl[T]('hasher) }

  private def extractCaseInfoImpl[T: Type](hasher: Expr[CaseHasher])(using Quotes): Expr[Array[CaseInfo]] =
    import quotes.reflect.*

    val tpe    = TypeRepr.of[T]
    val symbol = tpe.typeSymbol

    if !symbol.flags.is(Flags.Sealed) then
      report.errorAndAbort(
        s"Type ${symbol.fullName} must be a sealed trait or enum for Finite derivation"
      )

    val cases = Leaves.sealedLeaves(symbol) match
      case Right(leaves) => leaves
      case Left(error)   => report.errorAndAbort(finiteLeafMessage(symbol, error))

    // Detect which hasher is being used
    val (hashFn, hasherName) = detectHasher(hasher)

    // Extract case names and compute hashes at compile time
    val caseInfosWithHashes = cases.map { caseSymbol =>
      val simpleName = caseSymbol.name
      val fullName   = caseSymbol.fullName
      val hash       = hashFn(fullName)
      (simpleName, fullName, hash)
    }

    // Check for collisions at compile time
    val hashToNames = caseInfosWithHashes.groupBy(_._3)
    val collisions  = hashToNames.filter(_._2.size > 1)

    if collisions.nonEmpty then
      val collisionMsg = collisions
        .map { case (hash, cases) =>
          s"  Hash $hash: ${cases.map(_._2).mkString(", ")}"
        }
        .mkString("\n")
      report.errorAndAbort(
        s"Hash collision detected in sealed type ${symbol.fullName} using $hasherName hasher!\n" +
          s"The following cases have the same hash:\n$collisionMsg\n" +
          s"Consider using CaseHasher.murmur3 for better distribution."
      )

    // Generate array with pre-computed hashes
    val caseInfoExprs = caseInfosWithHashes.map { case (simple, full, hash) =>
      '{ CaseInfo(${ Expr(simple) }, ${ Expr(full) }, ${ Expr(hash) }) }
    }

    '{ Array(${ Varargs(caseInfoExprs) }*) }
  end extractCaseInfoImpl

  /** Extract hierarchy information for a sealed type at compile time.
    *
    * Returns a HierarchyInfo containing a mapping from each parent (sealed trait) hash to the set of all its leaf
    * descendant hashes. This enables `whenAny[ParentState]` to expand to transitions for all leaf states.
    *
    * For example, given:
    * {{{
    * sealed trait DocumentState
    * case object Draft extends DocumentState
    * sealed trait InReview extends DocumentState
    *   case object PendingReview extends InReview
    *   case object UnderReview extends InReview
    * }}}
    *
    * The hierarchy info would map:
    *   - hash(DocumentState) -> {hash(Draft), hash(PendingReview), hash(UnderReview)}
    *   - hash(InReview) -> {hash(PendingReview), hash(UnderReview)}
    */
  inline def extractHierarchyInfo[T](inline hasher: CaseHasher): HierarchyInfo =
    ${ extractHierarchyInfoImpl[T]('hasher) }

  private def extractHierarchyInfoImpl[T: Type](hasher: Expr[CaseHasher])(using Quotes): Expr[HierarchyInfo] =
    import quotes.reflect.*

    val tpe    = TypeRepr.of[T]
    val symbol = tpe.typeSymbol

    if !symbol.flags.is(Flags.Sealed) then
      report.errorAndAbort(
        s"Type ${symbol.fullName} must be a sealed trait or enum for hierarchy extraction"
      )

    val (hashFn, _) = detectHasher(hasher)

    // Each sealed parent maps to the full names of its leaves. A diamond aborts.
    def collectHierarchy(sym: Symbol): Either[Leaves.LeafError, List[(String, Set[String])]] =
      Leaves.sealedLeaves(sym).flatMap { leaves =>
        val selfEntry =
          if sym.flags.is(Flags.Sealed) && leaves.nonEmpty then List((sym.fullName, leaves.map(_.fullName).toSet))
          else Nil
        sym.children.foldLeft[Either[Leaves.LeafError, List[(String, Set[String])]]](Right(selfEntry)) { (acc, child) =>
          if child.flags.is(Flags.Sealed) then acc.flatMap(soFar => collectHierarchy(child).map(soFar ++ _))
          else acc
        }
      }

    val hierarchy = collectHierarchy(symbol) match
      case Right(entries) => entries
      case Left(error)    => report.errorAndAbort(finiteLeafMessage(symbol, error))

    // Convert names to hashes
    val parentToLeavesMap: Map[Int, Set[Int]] = hierarchy.map { case (parentName, leafNames) =>
      hashFn(parentName) -> leafNames.map(hashFn)
    }.toMap

    // Generate the expression
    val mapEntries = parentToLeavesMap.toList.map { case (parentHash, leafHashes) =>
      val leafSetExpr = Expr(leafHashes)
      '{ ${ Expr(parentHash) } -> $leafSetExpr }
    }

    '{ HierarchyInfo(Map(${ Varargs(mapEntries) }*)) }
  end extractHierarchyInfoImpl

  /** Get the hash of a type at compile time.
    *
    * This is used by `whenAny[T]` to get the hash of the parent type parameter.
    */
  inline def typeHash[T](inline hasher: CaseHasher): Int =
    ${ typeHashImpl[T]('hasher) }

  private def typeHashImpl[T: Type](hasher: Expr[CaseHasher])(using Quotes): Expr[Int] =
    import quotes.reflect.*
    val tpe         = TypeRepr.of[T]
    val (hashFn, _) = detectHasher(hasher)

    // For singleton types (like A.type), we need to get the underlying term's symbol
    // This is especially important for Scala 3 enums where .type gives the parent enum type
    val fullName = tpe.dealias match
      case TermRef(_, _) =>
        // This is a reference to a term (value) - use termSymbol for the actual case
        val termSymbol = tpe.termSymbol
        if termSymbol.exists then termSymbol.fullName
        else tpe.typeSymbol.fullName
      case _ =>
        tpe.typeSymbol.fullName

    Expr(hashFn(fullName))
  end typeHashImpl

  /** Detect the hasher type from the expression and return a compile-time hash function.
    *
    * Detection is based on the type of the hasher expression. For murmur3 to be detected, use the specific type:
    * `given CaseHasher.Murmur3.type = CaseHasher.murmur3`
    */
  private def detectHasher(hasher: Expr[CaseHasher])(using Quotes): (String => Int, String) =
    import quotes.reflect.*

    val murmur3Type = TypeRepr.of[CaseHasher.Murmur3.type]
    val hasherType  = hasher.asTerm.tpe.dealias

    if hasherType <:< murmur3Type then (scala.util.hashing.MurmurHash3.stringHash, "murmur3")
    else (_.hashCode, "default")
  end detectHasher

  /** Pattern-match every leaf and return its pre-computed full-name hash. The match has no default arm. */
  inline def generateCaseHash[T](inline hasher: CaseHasher): T => Int =
    ${ generateCaseHashImpl[T]('hasher) }

  private def generateCaseHashImpl[T: Type](hasher: Expr[CaseHasher])(using Quotes): Expr[T => Int] =
    import quotes.reflect.*

    val tpe    = TypeRepr.of[T]
    val symbol = tpe.typeSymbol

    if !symbol.flags.is(Flags.Sealed) then
      report.errorAndAbort(
        s"Type ${symbol.fullName} must be a sealed trait or enum"
      )

    val cases = Leaves.sealedLeaves(symbol) match
      case Right(leaves) => leaves
      case Left(error)   => report.errorAndAbort(finiteLeafMessage(symbol, error))

    val (hashFn, _) = detectHasher(hasher)

    if symbol.flags.is(Flags.Enum) then enumOrdinalHash[T](cases, hashFn)
    else
      // Sealed-trait case classes are real classes. A typed wildcard is a checked type test.
      def patternFor(sym: Symbol): Tree =
        if sym.flags.is(Flags.Module) then
          val term =
            if sym.name.endsWith("$") && sym.companionModule.exists then sym.companionModule
            else sym
          Ref(term)
        else Typed(Wildcard(), Inferred(sym.typeRef))

      def hashMatch(scrutinee: Expr[T]): Expr[Int] =
        val branches = cases.map { sym =>
          CaseDef(patternFor(sym), None, Literal(IntConstant(hashFn(sym.fullName))))
        }
        Match(scrutinee.asTerm, branches).asExprOf[Int]

      '{ (value: T) =>
        ${ hashMatch('value) }
      }
    end if
  end generateCaseHashImpl

  /** Enum ordinal is the index of `symbol.children`. A child that is not a leaf is an error, not hash `-1`. */
  private def enumOrdinalHash[T: Type](using
      Quotes
  )(
      cases: List[quotes.reflect.Symbol],
      hashFn: String => Int,
  ): Expr[T => Int] =
    import quotes.reflect.*
    val parent = TypeRepr.of[T].typeSymbol
    val byFull = cases.map(sym => sym.fullName -> hashFn(sym.fullName)).toMap
    val hashes = parent.children.map { child =>
      byFull.get(child.fullName) match
        case Some(hash) => hash
        case None       =>
          report.errorAndAbort(
            s"${child.name} is not a leaf of ${parent.name}. A leaf has one sealed parent."
          )
    }
    val table = Expr(hashes.toArray)
    // The quote is checked where T is abstract, so `value: Enum` does not typecheck.
    // Flags.Enum means T is a Scala 3 enum, whose ordinal is the child index.
    '{ (value: T) =>
      val ordinal = value.asInstanceOf[scala.reflect.Enum].ordinal
      $table(ordinal)
    }

  private def finiteLeafMessage(using Quotes)(symbol: quotes.reflect.Symbol, error: Leaves.LeafError): String =
    error match
      case Leaves.LeafError.NotATree(_, _) =>
        Leaves.message(error)
      case Leaves.LeafError.Empty(_) =>
        s"Sealed type ${symbol.fullName} has no leaf cases. Ensure it has at least one case class or case object."
      case Leaves.LeafError.NotFinite(_) =>
        s"Type ${symbol.fullName} must be a sealed trait or enum for Finite derivation"

end FiniteMacros
