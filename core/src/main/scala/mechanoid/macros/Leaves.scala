package mechanoid.macros

import scala.quoted.*

/** What a type denotes as a set of Finite leaves.
  *
  * `sealedLeaves` walks one sealed tree. A leaf reached through two children is `NotATree`. `denote` is the matcher
  * reading: aliases, unions, one leaf, or a sealed parent. It does not accept an opaque alias, and Finite derivation
  * does not call it.
  */
private[mechanoid] object Leaves:

  enum LeafError:
    case NotFinite(shown: String)
    case Empty(shown: String)
    case NotATree(shown: String, leaf: String)

  def message(error: LeafError): String = error match
    case LeafError.NotFinite(shown) =>
      s"$shown is not a finite set of leaves."
    case LeafError.Empty(shown) =>
      s"$shown has no leaf cases."
    case LeafError.NotATree(shown, leaf) =>
      s"$leaf is reached through more than one sealed parent under $shown. A leaf has one sealed parent. Name an overlapping group with a union."

  /** Simple name of the type the user wrote. A union is shown as the union. An alias keeps its name. */
  def shown(using Quotes)(tpe: quotes.reflect.TypeRepr): String =
    import quotes.reflect.*
    tpe match
      case _: OrType => tpe.show
      case _         =>
        val sym = tpe.typeSymbol
        if sym.exists && sym.name.nonEmpty && sym.name != "<none>" then sym.name
        else tpe.show

  /** Symbol Finite hashes for a leaf type. Same walk `state[T]` uses for a real leaf. */
  def leafSymbol(using Quotes)(tpe: quotes.reflect.TypeRepr): quotes.reflect.Symbol =
    import quotes.reflect.*
    def isLeaf(sym: Symbol): Boolean =
      sym.flags.is(Flags.Case) || sym.flags.is(Flags.Enum) || sym.flags.is(Flags.Module)

    val d = tpe.dealias
    d match
      case TermRef(prefix, _) =>
        val ts = d.termSymbol
        if !ts.exists then d.typeSymbol
        else if isLeaf(ts) then ts
        else leafSymbol(prefix.memberType(ts).dealias)
      case _ => d.typeSymbol
  end leafSymbol

  /** Leaves under one sealed symbol. Declaration order. A repeated full name is `NotATree`. */
  def sealedLeaves(using Quotes)(sym: quotes.reflect.Symbol): Either[LeafError, List[quotes.reflect.Symbol]] =
    val shownName = if sym.exists then sym.name else sym.toString
    if !sym.exists then Left(LeafError.NotFinite(shownName))
    else if isParent(sym) then
      collect(sym, shownName).flatMap { leaves =>
        if leaves.isEmpty then Left(LeafError.Empty(shownName)) else Right(leaves)
      }
    else if isWalkLeaf(sym) then Right(List(sym))
    else Left(LeafError.NotFinite(shownName))

  /** A sealed parent or a union. One child does not make a parent into a leaf. */
  def isGroup(using Quotes)(tpe: quotes.reflect.TypeRepr): Boolean =
    import quotes.reflect.*
    if isOpaque(tpe) then false
    else
      tpe.dealias match
        case _: OrType  => true
        case _: AndType => false
        case other      => isParent(leafSymbol(other))

  /** Matcher denotation of a type: alias, union, one leaf, or a sealed parent. */
  def denote(using Quotes)(tpe: quotes.reflect.TypeRepr): Either[LeafError, List[quotes.reflect.Symbol]] =
    val label = shown(tpe)
    if isOpaque(tpe) then Left(LeafError.NotFinite(label))
    else expand(tpe.dealias, label)

  private def expand(using
      Quotes
  )(
      tpe: quotes.reflect.TypeRepr,
      label: String,
  ): Either[LeafError, List[quotes.reflect.Symbol]] =
    import quotes.reflect.*
    tpe match
      case OrType(left, right) =>
        unionSide(left).flatMap { ls =>
          unionSide(right).map { rs =>
            (ls ++ rs).distinctBy(_.fullName)
          }
        }
      case _: AndType =>
        Left(LeafError.NotFinite(label))
      case other =>
        val sym = leafSymbol(other)
        if isParent(sym) then
          sealedLeaves(sym).left.map {
            case LeafError.NotATree(_, leaf) => LeafError.NotATree(label, leaf)
            case LeafError.Empty(_)          => LeafError.Empty(label)
            case LeafError.NotFinite(_)      => LeafError.NotFinite(label)
          }
        else if sym.exists && isDenotedLeaf(sym) then Right(List(sym))
        else Left(LeafError.NotFinite(label))
    end match
  end expand

  /** `Empty` is the empty set inside a union. `all[Empty]` itself never goes through here. */
  private def unionSide(using Quotes)(tpe: quotes.reflect.TypeRepr): Either[LeafError, List[quotes.reflect.Symbol]] =
    denote(tpe) match
      case Left(LeafError.Empty(_)) => Right(Nil)
      case other                    => other

  private def collect(using
      Quotes
  )(
      sym: quotes.reflect.Symbol,
      shownName: String,
  ): Either[LeafError, List[quotes.reflect.Symbol]] =
    import quotes.reflect.*
    sym.children.foldLeft[Either[LeafError, List[quotes.reflect.Symbol]]](Right(Nil)) { (acc, child) =>
      acc.flatMap { soFar =>
        if child.flags.is(Flags.Sealed) then
          collect(child, shownName).flatMap(more => addLeaves(soFar, more, shownName))
        else if isWalkLeaf(child) then addLeaves(soFar, List(child), shownName)
        else Right(soFar)
      }
    }
  end collect

  private def addLeaves(using
      Quotes
  )(
      soFar: List[quotes.reflect.Symbol],
      more: List[quotes.reflect.Symbol],
      shownName: String,
  ): Either[LeafError, List[quotes.reflect.Symbol]] =
    more.foldLeft[Either[LeafError, List[quotes.reflect.Symbol]]](Right(soFar)) { (acc, leaf) =>
      acc.flatMap { xs =>
        if xs.exists(_.fullName == leaf.fullName) then
          val leafName = leaf.name
          val label    = if leafName.endsWith("$") then leafName.dropRight(1) else leafName
          Left(LeafError.NotATree(shownName, label))
        else Right(xs :+ leaf)
      }
    }

  private def isOpaque(using Quotes)(tpe: quotes.reflect.TypeRepr): Boolean =
    import quotes.reflect.*
    val sym = tpe.typeSymbol
    sym.exists && sym.flags.is(Flags.Opaque)

  private def isParent(using Quotes)(sym: quotes.reflect.Symbol): Boolean =
    import quotes.reflect.*
    sym.exists && sym.flags.is(Flags.Sealed) && sym.children.nonEmpty

  /** Child walk. Matches the historical Finite predicate so case lists do not grow a plain module. */
  private def isWalkLeaf(using Quotes)(sym: quotes.reflect.Symbol): Boolean =
    import quotes.reflect.*
    sym.flags.is(Flags.Case) || sym.flags.is(Flags.Enum)

  /** A singleton type's term may be a module whose class symbol is the case. */
  private def isDenotedLeaf(using Quotes)(sym: quotes.reflect.Symbol): Boolean =
    import quotes.reflect.*
    isWalkLeaf(sym) || sym.flags.is(Flags.Module)
end Leaves
