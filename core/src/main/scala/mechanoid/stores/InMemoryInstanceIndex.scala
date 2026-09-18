package mechanoid.stores

import java.time.Instant
import zio.*
import mechanoid.core.*
import mechanoid.persistence.*

/** Ref-based in-memory [[InstanceIndex]].
  *
  * Forward map is `Alias -> Id`; reverse map is `Id -> Set[Alias]`. Index maps are `IndexKey -> Map[Id, IndexRow]` and
  * `Id -> Set[IndexKey]` so [[find]] / [[unbindInstance]] do not scan other keys.
  */
final class InMemoryInstanceIndex[Id] private (
    stateRef: Ref[InMemoryInstanceIndex.State[Id]]
) extends InstanceIndex[Id]:

  import InMemoryInstanceIndex.*

  override def bindAll(aliases: Chunk[Alias], instanceId: Id): ZIO[Any, MechanoidError, Unit] =
    if aliases.isEmpty then ZIO.unit
    else
      stateRef
        .modify { state =>
          aliases.find { alias =>
            state.forward.get(alias).exists(_ != instanceId)
          } match
            case Some(alias) =>
              val heldBy = state.forward(alias)
              val error  = UniqueAliasError(alias.namespace, alias.key, heldBy.toString, instanceId.toString)
              (Left(error), state)
            case None =>
              val toAdd = aliases.filterNot(a => state.forward.get(a).contains(instanceId))
              if toAdd.isEmpty then (Right(()), state)
              else
                val nextForward = state.forward ++ toAdd.map(_ -> instanceId)
                val current     = state.reverse.getOrElse(instanceId, Set.empty)
                val nextReverse = state.reverse + (instanceId -> (current ++ toAdd.toSet))
                (Right(()), state.copy(forward = nextForward, reverse = nextReverse))
        }
        .flatMap {
          case Left(error) => ZIO.fail(error)
          case Right(_)    => ZIO.unit
        }

  override def resolve(alias: Alias): ZIO[Any, MechanoidError, Option[Id]] =
    stateRef.get.map(_.forward.get(alias))

  override def unbindAll(aliases: Chunk[Alias]): ZIO[Any, MechanoidError, Long] =
    if aliases.isEmpty then ZIO.succeed(0L)
    else
      stateRef.modify { state =>
        val existing = aliases.filter(state.forward.contains)
        if existing.isEmpty then (0L, state)
        else
          val nextForward = existing.foldLeft(state.forward)(_ - _)
          val nextReverse = existing.foldLeft(state.reverse) { (acc, alias) =>
            val id = state.forward(alias)
            acc.get(id) match
              case Some(set) =>
                val next = set - alias
                if next.isEmpty then acc - id else acc + (id -> next)
              case None => acc
          }
          (existing.size.toLong, state.copy(forward = nextForward, reverse = nextReverse))
        end if
      }

  override def aliasesOf(instanceId: Id, namespace: Option[String] = None): ZIO[Any, MechanoidError, Chunk[Alias]] =
    stateRef.get.map { state =>
      val all = Chunk.fromIterable(state.reverse.getOrElse(instanceId, Set.empty))
      namespace match
        case Some(ns) => all.filter(_.namespace == ns)
        case None     => all
    }

  override def unbindInstance(instanceId: Id): ZIO[Any, MechanoidError, Long] =
    stateRef.modify { state =>
      val aliasN      = state.reverse.get(instanceId).map(_.size).getOrElse(0)
      val indexN      = state.indexReverse.get(instanceId).map(_.size).getOrElse(0)
      val nextForward = state.reverse.get(instanceId) match
        case Some(set) => set.foldLeft(state.forward)(_ - _)
        case None      => state.forward
      val nextIndexForward = state.indexReverse.get(instanceId) match
        case None      => state.indexForward
        case Some(set) =>
          set.foldLeft(state.indexForward) { (acc, key) =>
            acc.get(key) match
              case Some(rows) =>
                val next = rows - instanceId
                if next.isEmpty then acc - key else acc + (key -> next)
              case None => acc
          }
      val next = state.copy(
        forward = nextForward,
        reverse = state.reverse - instanceId,
        indexForward = nextIndexForward,
        indexReverse = state.indexReverse - instanceId,
      )
      ((aliasN + indexN).toLong, next)
    }

  override def bindIndexes(
      keys: Chunk[IndexKey],
      instanceId: Id,
      meta: IndexMeta,
  ): ZIO[Any, MechanoidError, Unit] =
    if keys.isEmpty then ZIO.unit
    else
      stateRef.update { state =>
        val distinct = keys.distinct
        val row      = IndexRow(
          stateName = meta.stateName,
          startedAt = meta.startedAt,
          touchedAt = meta.touchedAt,
          createdAt = meta.createdAt,
          editedAt = meta.editedAt,
          rank = meta.rank,
        )
        distinct.foldLeft(state) { (acc, key) =>
          val currentRows = acc.indexForward.getOrElse(key, Map.empty)
          val nextRow     = currentRows.get(instanceId) match
            case Some(existing) =>
              existing.copy(
                stateName = row.stateName,
                touchedAt = row.touchedAt,
                editedAt = row.editedAt,
                rank = row.rank,
              )
            case None => row
          val nextForward = acc.indexForward + (key -> (currentRows + (instanceId -> nextRow)))
          val currentSet  = acc.indexReverse.getOrElse(instanceId, Set.empty)
          acc.copy(
            indexForward = nextForward,
            indexReverse = acc.indexReverse + (instanceId -> (currentSet + key)),
          )
        }
      }

  override def unbindIndexes(keys: Chunk[IndexKey], instanceId: Id): ZIO[Any, MechanoidError, Long] =
    if keys.isEmpty then ZIO.succeed(0L)
    else
      stateRef.modify { state =>
        val removed = keys.distinct.filter { key =>
          state.indexForward.get(key).exists(_.contains(instanceId))
        }
        if removed.isEmpty then (0L, state)
        else
          val nextForward = removed.foldLeft(state.indexForward) { (acc, key) =>
            acc.get(key) match
              case Some(rows) =>
                val next = rows - instanceId
                if next.isEmpty then acc - key else acc + (key -> next)
              case None => acc
          }
          val nextReverse = state.indexReverse.get(instanceId) match
            case Some(set) =>
              val next = set -- removed.toSet
              if next.isEmpty then state.indexReverse - instanceId
              else state.indexReverse + (instanceId -> next)
            case None => state.indexReverse
          (removed.size.toLong, state.copy(indexForward = nextForward, indexReverse = nextReverse))
        end if
      }

  override def touchIndex(instanceId: Id, meta: IndexMeta): ZIO[Any, MechanoidError, Long] =
    stateRef.modify { state =>
      state.indexReverse.get(instanceId) match
        case None      => (0L, state)
        case Some(set) =>
          val nextForward = set.foldLeft(state.indexForward) { (acc, key) =>
            acc.get(key) match
              case Some(rows) if rows.contains(instanceId) =>
                val updated = rows(instanceId).copy(
                  stateName = meta.stateName,
                  touchedAt = meta.touchedAt,
                  editedAt = meta.editedAt,
                  rank = meta.rank,
                )
                acc + (key -> (rows + (instanceId -> updated)))
              case _ => acc
          }
          (set.size.toLong, state.copy(indexForward = nextForward))
    }

  override def find(query: IndexQuery[Id])(using Ordering[Id]): ZIO[Any, MechanoidError, IndexPage[Id]] =
    if query.startAfter.isDefined && query.startBefore.isDefined then
      ZIO.fail(InvalidIndexQuery("startAfter and startBefore are mutually exclusive"))
    else if query.since.isDefined && !IndexQueryEval.isClockSort(query.sort) then
      ZIO.fail(InvalidIndexQuery("since applies only to clock sorts, not Rank"))
    else
      stateRef.get.map { state =>
        IndexQueryEval.page(coveringRows(state, query), query)
      }

  override def count(query: IndexQuery[Id])(using Ordering[Id]): ZIO[Any, MechanoidError, Long] =
    find(query.copy(limit = IndexQuery.MaxLimit)).map(_.items.size.toLong)

  private def coveringRows(state: State[Id], query: IndexQuery[Id]): Iterable[(Id, IndexQueryEval.Covering)] =
    val required = requiredIds(state, query.require)
    state.indexForward
      .getOrElse(query.key, Map.empty)
      .iterator
      .collect {
        case (id, row) if required.forall(_.contains(id)) =>
          id -> IndexQueryEval.Covering(
            row.stateName,
            row.startedAt,
            row.touchedAt,
            row.createdAt,
            row.editedAt,
            row.rank,
          )
      }
      .toSeq
  end coveringRows

  private def requiredIds(state: State[Id], require: Chunk[IndexKey]): Option[Set[Id]] =
    require.toList match
      case Nil    => None
      case h :: t =>
        Some(
          t.foldLeft(state.indexForward.getOrElse(h, Map.empty).keySet) { (acc, k) =>
            acc.intersect(state.indexForward.getOrElse(k, Map.empty).keySet)
          }
        )

  override def count(key: IndexKey, filter: IndexFilter = IndexFilter.All): ZIO[Any, MechanoidError, Long] =
    stateRef.get.map { state =>
      state.indexForward
        .getOrElse(key, Map.empty)
        .values
        .count(row => InMemoryInstanceIndex.matchesFilter(row, filter))
        .toLong
    }

  override def countsByState(key: IndexKey): ZIO[Any, MechanoidError, Chunk[(String, Long)]] =
    stateRef.get.map { state =>
      val grouped = state.indexForward
        .getOrElse(key, Map.empty)
        .values
        .groupBy(_.stateName)
        .view
        .map { (name, rows) => name -> rows.size.toLong }
        .toSeq
        .sortBy(_._1)
      Chunk.fromIterable(grouped)
    }

  override def indexesOf(
      instanceId: Id,
      namespace: Option[String] = None,
  ): ZIO[Any, MechanoidError, Chunk[IndexKey]] =
    stateRef.get.map { state =>
      val all = Chunk.fromIterable(state.indexReverse.getOrElse(instanceId, Set.empty))
      namespace match
        case Some(ns) => all.filter(_.namespace == ns)
        case None     => all
    }

  def clear: UIO[Unit] =
    stateRef.set(State.empty)
end InMemoryInstanceIndex

object InMemoryInstanceIndex:

  private[stores] final case class IndexRow(
      stateName: String,
      startedAt: Instant,
      touchedAt: Instant,
      createdAt: Instant,
      editedAt: Instant,
      rank: Long = 0L,
  )

  private[stores] final case class State[Id](
      forward: Map[Alias, Id],
      reverse: Map[Id, Set[Alias]],
      indexForward: Map[IndexKey, Map[Id, IndexRow]],
      indexReverse: Map[Id, Set[IndexKey]],
  )

  private[stores] object State:
    def empty[Id]: State[Id] = State(Map.empty, Map.empty, Map.empty, Map.empty)

  private[stores] def matchesFilter(row: IndexRow, filter: IndexFilter): Boolean =
    IndexQueryEval.matchesFilter(row.stateName, filter)

  def make[Id]: UIO[InMemoryInstanceIndex[Id]] =
    Ref.make(State.empty[Id]).map(new InMemoryInstanceIndex(_))

  def layer[Id: Tag]: ULayer[InstanceIndex[Id]] =
    ZLayer.fromZIO(make[Id])
end InMemoryInstanceIndex
