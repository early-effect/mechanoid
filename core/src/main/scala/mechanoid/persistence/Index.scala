package mechanoid.persistence

import java.time.Instant
import scala.annotation.unused
import zio.Chunk

/** Non-unique secondary key that many instances may share. Encoding; construct via [[IndexQuery.of]]. */
final case class IndexKey(namespace: String, key: String):
  override def toString: String = s"$namespace/$key"

final case class IndexClocks(createdAt: Option[Instant], editedAt: Option[Instant])

object IndexClocks:
  def apply(createdAt: Instant, editedAt: Instant): IndexClocks =
    IndexClocks(Some(createdAt), Some(editedAt))
  val none: IndexClocks = IndexClocks(None, None)

final case class IndexMeta(
    stateName: String,
    startedAt: Instant,
    touchedAt: Instant,
    clocks: Option[IndexClocks],
    rank: Long = 0L,
):
  def createdAt: Instant = clocks.flatMap(_.createdAt).getOrElse(startedAt)
  def editedAt: Instant  = clocks.flatMap(_.editedAt).getOrElse(touchedAt)

final case class Indexed[Id](
    instanceId: Id,
    stateName: String,
    startedAt: Instant,
    touchedAt: Instant,
    createdAt: Instant,
    editedAt: Instant,
    rank: Long = 0L,
)

enum IndexFilter:
  case All
  case Only(states: Set[String])
  case Except(states: Set[String])

enum IndexSort:
  case EditedDesc, EditedAsc
  case TouchedDesc, TouchedAsc
  case CreatedDesc, CreatedAsc
  case StartedDesc, StartedAsc
  case RankDesc, RankAsc

enum IndexScalar:
  case Time(value: Instant)
  case Rank(value: Long)

  def compareTo(other: IndexScalar): Int =
    (this, other) match
      case (Time(a), Time(b)) => a.compareTo(b)
      case (Rank(a), Rank(b)) => java.lang.Long.compare(a, b)
      case (Time(_), Rank(_)) => -1
      case (Rank(_), Time(_)) => 1
end IndexScalar

final case class IndexCursor[Id](value: IndexScalar, instanceId: Id)

object IndexCursor:
  def of[Id](row: Indexed[Id], sort: IndexSort): IndexCursor[Id] =
    IndexCursor(IndexQueryEval.sortValue(rowToCovering(row), sort), row.instanceId)

  private def rowToCovering[Id](row: Indexed[Id]): IndexQueryEval.Covering =
    IndexQueryEval.Covering(
      row.stateName,
      row.startedAt,
      row.touchedAt,
      row.createdAt,
      row.editedAt,
      row.rank,
    )
end IndexCursor

final case class IndexPage[Id](
    items: Chunk[Indexed[Id]],
    cursor: Option[IndexCursor[Id]],
    hasMore: Boolean,
    pageNumber: Int = 0,
)

final case class IndexQuery[Id](
    key: IndexKey,
    require: Chunk[IndexKey] = Chunk.empty,
    filter: IndexFilter = IndexFilter.All,
    sort: IndexSort = IndexSort.EditedDesc,
    since: Option[Instant] = None,
    rankMin: Option[Long] = None,
    rankMax: Option[Long] = None,
    startAfter: Option[IndexCursor[Id]] = None,
    startBefore: Option[IndexCursor[Id]] = None,
    limit: Int = IndexQuery.DefaultLimit,
)

object IndexQuery:
  val DefaultLimit = 50
  val MaxLimit     = 1024

  transparent inline def of[S](using @unused ev: mechanoid.core.Finite[S]) =
    mechanoid.macros.NsMacros.indexQueryOf[S]

  def effectiveLimit(limit: Int): Int =
    if limit < 1 then 1
    else math.min(limit, MaxLimit)
end IndexQuery
