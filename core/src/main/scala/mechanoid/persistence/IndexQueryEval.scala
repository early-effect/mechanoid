package mechanoid.persistence

import java.time.Instant
import zio.Chunk

/** Pure paging of rows already loaded for one [[IndexKey]]. */
object IndexQueryEval:

  final case class Covering(
      stateName: String,
      startedAt: Instant,
      touchedAt: Instant,
      createdAt: Instant,
      editedAt: Instant,
      rank: Long = 0L,
  )

  def sortValue(row: Covering, sort: IndexSort): IndexScalar =
    sort match
      case IndexSort.EditedDesc | IndexSort.EditedAsc   => IndexScalar.Time(row.editedAt)
      case IndexSort.TouchedDesc | IndexSort.TouchedAsc => IndexScalar.Time(row.touchedAt)
      case IndexSort.CreatedDesc | IndexSort.CreatedAsc => IndexScalar.Time(row.createdAt)
      case IndexSort.StartedDesc | IndexSort.StartedAsc => IndexScalar.Time(row.startedAt)
      case IndexSort.RankDesc | IndexSort.RankAsc       => IndexScalar.Rank(row.rank)

  def isDesc(sort: IndexSort): Boolean =
    sort match
      case IndexSort.EditedDesc | IndexSort.TouchedDesc | IndexSort.CreatedDesc | IndexSort.StartedDesc |
          IndexSort.RankDesc =>
        true
      case _ => false

  def isClockSort(sort: IndexSort): Boolean =
    sort match
      case IndexSort.RankDesc | IndexSort.RankAsc => false
      case _                                      => true

  def matchesFilter(stateName: String, filter: IndexFilter): Boolean =
    filter match
      case IndexFilter.All           => true
      case IndexFilter.Only(names)   => names.contains(stateName)
      case IndexFilter.Except(names) => !names.contains(stateName)

  def page[Id](rows: Iterable[(Id, Covering)], query: IndexQuery[Id])(using Ordering[Id]): IndexPage[Id] =
    val limit    = IndexQuery.effectiveLimit(query.limit)
    val sort     = query.sort
    val ordId    = summon[Ordering[Id]]
    val filtered = rows.iterator.filter { (id, row) =>
      matchesFilter(row.stateName, query.filter) &&
      query.rankMin.forall(row.rank >= _) &&
      query.rankMax.forall(row.rank <= _) &&
      sinceOk(row, query) &&
      query.startAfter.forall(c => isAfter(id, row, c, sort, ordId)) &&
      query.startBefore.forall(c => isBefore(id, row, c, sort, ordId))
    }.toList
    val ordered = filtered.sortWith { (a, b) =>
      val c = sortValue(a._2, sort).compareTo(sortValue(b._2, sort))
      if c != 0 then if isDesc(sort) then c > 0 else c < 0
      else ordId.compare(a._1, b._1) < 0
    }
    val taken =
      if query.startBefore.isDefined then ordered.takeRight(limit) else ordered.take(limit)
    val items = Chunk.fromIterable(taken.map { (id, row) =>
      Indexed(id, row.stateName, row.startedAt, row.touchedAt, row.createdAt, row.editedAt, row.rank)
    })
    val cursor = items.lastOption.map(IndexCursor.of(_, sort))
    IndexPage(items, cursor, hasMore = items.size == limit)
  end page

  private def sinceOk[Id](row: Covering, query: IndexQuery[Id]): Boolean =
    query.since match
      case None    => true
      case Some(t) =>
        if !isClockSort(query.sort) then true
        else
          sortValue(row, query.sort) match
            case IndexScalar.Time(v) => !v.isBefore(t)
            case _                   => true

  private def isAfter[Id](id: Id, row: Covering, cursor: IndexCursor[Id], sort: IndexSort, ord: Ordering[Id]): Boolean =
    val cmp = sortValue(row, sort).compareTo(cursor.value)
    if isDesc(sort) then cmp < 0 || (cmp == 0 && ord.gt(id, cursor.instanceId))
    else cmp > 0 || (cmp == 0 && ord.gt(id, cursor.instanceId))

  private def isBefore[Id](
      id: Id,
      row: Covering,
      cursor: IndexCursor[Id],
      sort: IndexSort,
      ord: Ordering[Id],
  ): Boolean =
    val cmp = sortValue(row, sort).compareTo(cursor.value)
    if isDesc(sort) then cmp > 0 || (cmp == 0 && ord.lt(id, cursor.instanceId))
    else cmp < 0 || (cmp == 0 && ord.lt(id, cursor.instanceId))
  end isBefore
end IndexQueryEval
