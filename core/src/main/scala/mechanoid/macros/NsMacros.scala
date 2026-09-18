package mechanoid.macros

import scala.quoted.*
import mechanoid.core.{alias, index}
import mechanoid.persistence.{AliasOf, IndexNsBind, IndexQueryBuilder, IndexQueryOf, IndexRequireOf}

/** Compile-time namespace / leaf checks for `Alias.of` and `IndexQuery.of`. */
object NsMacros:

  transparent inline def indexQueryOf[S] =
    ${ indexQueryOfImpl[S] }

  transparent inline def aliasOf[S] =
    ${ aliasOfImpl[S] }

  transparent inline def requireOf[S](inline base: IndexQueryBuilder[S]) =
    ${ requireOfImpl[S]('base) }

  inline def indexBind[S](inline name: String): IndexNsBind[S] =
    ${ indexBindImpl[S]('name) }

  inline def aliasNs[S](inline name: String): String =
    ${ aliasNsImpl[S]('name) }

  inline def indexIdent[S](inline ident: Any): IndexNsBind[S] =
    ${ indexIdentImpl[S]('ident) }

  inline def aliasIdent[S](inline ident: Any): String =
    ${ aliasIdentImpl[S]('ident) }

  inline def leafName[S, L]: String =
    ${ leafNameImpl[S, L] }

  inline def parentLeafNames[S, P]: List[String] =
    ${ parentLeafNamesImpl[S, P] }

  private def indexQueryOfImpl[S: Type](using Quotes) =
    import quotes.reflect.*
    refine[S, IndexQueryOf[S]](indexAnnot = true, '{ IndexQueryOf[S]() }, TypeRepr.of[IndexNsBind[S]])

  private def aliasOfImpl[S: Type](using Quotes) =
    import quotes.reflect.*
    import mechanoid.persistence.AliasNsBind
    refine[S, AliasOf[S]](indexAnnot = false, '{ AliasOf[S]() }, TypeRepr.of[AliasNsBind[S]])

  private def requireOfImpl[S: Type](base: Expr[IndexQueryBuilder[S]])(using Quotes) =
    import quotes.reflect.*
    import mechanoid.persistence.IndexRequireBind
    refine[S, IndexRequireOf[S]](indexAnnot = true, '{ IndexRequireOf[S]($base) }, TypeRepr.of[IndexRequireBind[S]])

  /** Saferis `instanceOf` analog: refine the builder so `@index` / `@alias` fields are members. */
  private def refine[S: Type, B: Type](using
      Quotes
  )(
      indexAnnot: Boolean,
      make: Expr[B],
      bind: quotes.reflect.TypeRepr,
  ) =
    import quotes.reflect.*
    val annot   = if indexAnnot then TypeRepr.of[index].typeSymbol else TypeRepr.of[alias].typeSymbol
    val names   = annotatedNames(TypeRepr.of[S].typeSymbol, annot)
    val refined = names.toList.sorted.foldLeft(TypeRepr.of[B]) { (t, n) =>
      Refinement(t, n, bind)
    }
    refined.asType match
      case '[t] => '{ $make.asInstanceOf[t] }
  end refine

  private def indexBindImpl[S: Type](name: Expr[String])(using Quotes): Expr[IndexNsBind[S]] =
    val ns = literalName(name)
    validateMember[S](ns, indexAnnot = true)
    '{ IndexNsBind[S](${ Expr(ns) }) }

  private def aliasNsImpl[S: Type](name: Expr[String])(using Quotes): Expr[String] =
    val ns = literalName(name)
    validateMember[S](ns, indexAnnot = false)
    Expr(ns)

  private def indexIdentImpl[S: Type](ident: Expr[Any])(using Quotes): Expr[IndexNsBind[S]] =
    val ns = simpleName(ident)
    validateMember[S](ns, indexAnnot = true)
    '{ IndexNsBind[S](${ Expr(ns) }) }

  private def aliasIdentImpl[S: Type](ident: Expr[Any])(using Quotes): Expr[String] =
    val ns = simpleName(ident)
    validateMember[S](ns, indexAnnot = false)
    Expr(ns)

  /** Saferis analog of `extractFieldNameFromSelector`: Ident / Select name only, never a string literal. */
  inline def identName(inline ident: Any): String =
    ${ identNameImpl('ident) }

  private def identNameImpl(ident: Expr[Any])(using Quotes): Expr[String] =
    Expr(simpleName(ident))

  private def literalName(using Quotes)(name: Expr[String]): String =
    import quotes.reflect.*
    name.asTerm.underlyingArgument match
      case Literal(StringConstant(s)) => s
      case other                      =>
        report.errorAndAbort(
          s"Namespace must be a member select (got ${other.show}). Do not pass a string."
        )

  private def simpleName(using Quotes)(expr: Expr[Any]): String =
    import quotes.reflect.*
    def rec(term: Term): String =
      term.underlyingArgument match
        case Ident(n)                   => n
        case Select(_, n)               => n
        case Typed(inner, _)            => rec(inner)
        case Inlined(_, _, inner)       => rec(inner)
        case Block(_, e)                => rec(e)
        case Literal(StringConstant(_)) =>
          report.errorAndAbort(
            "Do not pass a string literal as a namespace. Use a member (selectDynamic) or an ident."
          )
        case other =>
          report.errorAndAbort(s"Expected a member ident like assignee, got: ${other.show}")
    rec(expr.asTerm)
  end simpleName

  private def validateMember[S: Type](using Quotes)(ns: String, indexAnnot: Boolean): Unit =
    import quotes.reflect.*
    val tpe   = TypeRepr.of[S]
    val annot = if indexAnnot then TypeRepr.of[index].typeSymbol else TypeRepr.of[alias].typeSymbol
    val names = annotatedNames(tpe.typeSymbol, annot)
    if names.nonEmpty && !names.contains(ns) then
      report.errorAndAbort(
        s"'$ns' is not an ${if indexAnnot then "@index" else "@alias"} member of ${tpe.typeSymbol.name}. " +
          s"Known: ${names.toList.sorted.mkString(", ")}"
      )
  end validateMember

  private def annotatedNames(using Quotes)(sym: quotes.reflect.Symbol, annot: quotes.reflect.Symbol): Set[String] =
    import quotes.reflect.*
    def paramsOf(s: Symbol): List[Symbol] =
      s.primaryConstructor.paramSymss.headOption.getOrElse(Nil)
    def leaves(s: Symbol): List[Symbol] =
      s.children.flatMap { child =>
        if child.flags.is(Flags.Sealed) then leaves(child)
        else if child.flags.is(Flags.Case) || child.flags.is(Flags.Enum) || child.isClassDef then List(child)
        else Nil
      }
    val cases = if sym.flags.is(Flags.Enum) || (sym.flags.is(Flags.Sealed) && sym.children.nonEmpty) then leaves(sym)
    else List(sym)
    cases.flatMap(paramsOf).filter(_.hasAnnotation(annot)).map(_.name).toSet
  end annotatedNames

  private def leafNameImpl[S: Type, L: Type](using Quotes): Expr[String] =
    import quotes.reflect.*
    val sSym  = TypeRepr.of[S].typeSymbol
    val lSym  = TypeRepr.of[L].typeSymbol
    val leaf  = lSym.name
    val known = leafSimpleNames(sSym)
    if !known.contains(leaf) && !known.exists(_ == leaf) then
      // also allow L to be a parent: handled by parentLeafNames
      val parents = parentNames(sSym)
      if !parents.contains(leaf) && !isLeafOrParent(sSym, lSym) then
        report.errorAndAbort(
          s"${lSym.name} is not a leaf or parent of ${sSym.name}. Known leaves: ${known.mkString(", ")}"
        )
    Expr(lSym.name)
  end leafNameImpl

  private def parentLeafNamesImpl[S: Type, P: Type](using Quotes): Expr[List[String]] =
    import quotes.reflect.*
    val sSym = TypeRepr.of[S].typeSymbol
    val pSym = TypeRepr.of[P].typeSymbol
    if !isLeafOrParent(sSym, pSym) then
      report.errorAndAbort(s"${pSym.name} is not in the Finite hierarchy of ${sSym.name}")
    val names = leafSimpleNames(pSym)
    if names.isEmpty then Expr(List(pSym.name))
    else Expr(names)

  private def isLeafOrParent(using Quotes)(root: quotes.reflect.Symbol, target: quotes.reflect.Symbol): Boolean =
    import quotes.reflect.*
    def rec(s: Symbol): Boolean =
      s == target || s.children.exists(rec)
    rec(root) || target == root

  private def leafSimpleNames(using Quotes)(sym: quotes.reflect.Symbol): List[String] =
    import quotes.reflect.*
    def leaves(s: Symbol): List[Symbol] =
      s.children.flatMap { child =>
        if child.flags.is(Flags.Sealed) then leaves(child)
        else if child.flags.is(Flags.Case) || child.flags.is(Flags.Enum) || child.isClassDef then List(child)
        else Nil
      }
    if sym.flags.is(Flags.Enum) || (sym.flags.is(Flags.Sealed) && sym.children.nonEmpty) then leaves(sym).map(_.name)
    else List(sym.name)
  end leafSimpleNames

  private def parentNames(using Quotes)(sym: quotes.reflect.Symbol): List[String] =
    import quotes.reflect.*
    def rec(s: Symbol): List[Symbol] =
      s :: s.children.filter(_.flags.is(Flags.Sealed)).flatMap(rec)
    rec(sym).map(_.name)
end NsMacros
