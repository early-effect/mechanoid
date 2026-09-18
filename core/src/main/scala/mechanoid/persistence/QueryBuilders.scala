package mechanoid.persistence

import java.time.Instant
import scala.annotation.unused
import scala.language.dynamics
import mechanoid.core.Finite
import mechanoid.macros.NsMacros
import mechanoid.machine.{AllMatcher, StateMatcher}
import zio.Chunk

/** Unique alias from a member of `S`. `Alias.of[InitiativeState].campaign(id)`. */
final class AliasOf[S]()(using @unused ev: Finite[S]) extends Selectable:
  def selectDynamic(name: String): AliasNsBind[S] =
    AliasNsBind(name)

  /** Handwritten extractors: `Alias.of[S].ns(campaign)(id)`. A string literal is a compile error. */
  transparent inline def ns(inline ident: Any): AliasNsBind[S] =
    AliasNsBind(NsMacros.aliasIdent[S](ident))

final class AliasNsBind[S](namespace: String):
  def apply[A: AliasCodec](value: A): Alias =
    Alias(namespace, summon[AliasCodec[A]].encode(value))

/** Many-to-one query. Pin `S` first: `IndexQuery.of[TicketState].assignee(me).only(state[Archived])`. */
final class IndexQueryOf[S: Finite]() extends Selectable:
  def selectDynamic(name: String): IndexNsBind[S] =
    IndexNsBind(name)

  /** Handwritten extractors: `IndexQuery.of[S].ns(assignee)(id)`. A string literal is a compile error. */
  transparent inline def ns(inline ident: Any): IndexNsBind[S] =
    NsMacros.indexIdent[S](ident)

final class IndexNsBind[S: Finite](val namespace: String):
  def apply[A: AliasCodec](value: A): IndexQueryBuilder[S] =
    IndexQueryBuilder(IndexKey(namespace, summon[AliasCodec[A]].encode(value)))

final class IndexRequireOf[S: Finite](base: IndexQueryBuilder[S]) extends Selectable:
  def selectDynamic(name: String): IndexRequireBind[S] =
    IndexRequireBind(base, name)

final class IndexRequireBind[S](base: IndexQueryBuilder[S], namespace: String)(using @unused ev: Finite[S]):
  def apply[A: AliasCodec](value: A): IndexQueryBuilder[S] =
    base.addRequire(IndexKey(namespace, summon[AliasCodec[A]].encode(value)))

final class IndexQueryBuilder[S](using @unused ev: Finite[S])(
    key: IndexKey,
    requireKeys: Chunk[IndexKey] = Chunk.empty,
    filter: IndexFilter = IndexFilter.All,
    sortBy: IndexSort = IndexSort.EditedDesc,
    sinceAt: Option[Instant] = None,
    rMin: Option[Long] = None,
    rMax: Option[Long] = None,
    after: Option[IndexCursor[Any]] = None,
    before: Option[IndexCursor[Any]] = None,
    lim: Int = IndexQuery.DefaultLimit,
):
  transparent inline def require = NsMacros.requireOf[S](this)

  private[persistence] def addRequire(k: IndexKey): IndexQueryBuilder[S] =
    copy(requireKeys = requireKeys :+ k)

  inline def only[L](@unused inline m: StateMatcher[L]): IndexQueryBuilder[S] =
    copy(filter = IndexFilter.Only(Set(NsMacros.leafName[S, L])))

  inline def only[P](@unused inline m: AllMatcher[P]): IndexQueryBuilder[S] =
    copy(filter = IndexFilter.Only(NsMacros.parentLeafNames[S, P].toSet))

  inline def only[L1, L2](
      @unused inline a: StateMatcher[L1],
      @unused inline b: StateMatcher[L2],
  ): IndexQueryBuilder[S] =
    copy(filter = IndexFilter.Only(Set(NsMacros.leafName[S, L1], NsMacros.leafName[S, L2])))

  inline def except[L](@unused inline m: StateMatcher[L]): IndexQueryBuilder[S] =
    copy(filter = IndexFilter.Except(Set(NsMacros.leafName[S, L])))

  def sort(s: IndexSort): IndexQueryBuilder[S] = copy(sortBy = s)
  def since(t: Instant): IndexQueryBuilder[S]  = copy(sinceAt = Some(t))
  def rankMin(n: Long): IndexQueryBuilder[S]   = copy(rMin = Some(n))
  def rankMax(n: Long): IndexQueryBuilder[S]   = copy(rMax = Some(n))
  def limit(n: Int): IndexQueryBuilder[S]      = copy(lim = n)

  def startAfter[Id](c: IndexCursor[Id]): IndexQueryBuilder[S] =
    copy(after = Some(c.asInstanceOf[IndexCursor[Any]]))

  def startBefore[Id](c: IndexCursor[Id]): IndexQueryBuilder[S] =
    copy(before = Some(c.asInstanceOf[IndexCursor[Any]]))

  def toQuery[Id]: IndexQuery[Id] =
    IndexQuery(
      key = key,
      require = requireKeys,
      filter = filter,
      sort = sortBy,
      since = sinceAt,
      rankMin = rMin,
      rankMax = rMax,
      startAfter = after.asInstanceOf[Option[IndexCursor[Id]]],
      startBefore = before.asInstanceOf[Option[IndexCursor[Id]]],
      limit = lim,
    )

  private def copy(
      key: IndexKey = key,
      requireKeys: Chunk[IndexKey] = requireKeys,
      filter: IndexFilter = filter,
      sortBy: IndexSort = sortBy,
      sinceAt: Option[Instant] = sinceAt,
      rMin: Option[Long] = rMin,
      rMax: Option[Long] = rMax,
      after: Option[IndexCursor[Any]] = after,
      before: Option[IndexCursor[Any]] = before,
      lim: Int = lim,
  ): IndexQueryBuilder[S] =
    IndexQueryBuilder(key, requireKeys, filter, sortBy, sinceAt, rMin, rMax, after, before, lim)
end IndexQueryBuilder
