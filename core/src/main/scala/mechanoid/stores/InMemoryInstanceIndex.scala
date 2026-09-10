package mechanoid.stores

import zio.*
import mechanoid.core.*
import mechanoid.persistence.{Alias, InstanceIndex}

/** Ref-based in-memory [[InstanceIndex]].
  *
  * Suitable for tests and single-process use. Forward map is `Alias -> Id`; reverse map is `Id -> Set[Alias]` so
  * [[aliasesOf]] / [[unbindInstance]] do not scan every alias.
  */
final class InMemoryInstanceIndex[Id] private (
    stateRef: Ref[InMemoryInstanceIndex.State[Id]]
) extends InstanceIndex[Id]:

  import InMemoryInstanceIndex.State

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
                (Right(()), State(nextForward, nextReverse))
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
          (existing.size.toLong, State(nextForward, nextReverse))
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
      state.reverse.get(instanceId) match
        case None      => (0L, state)
        case Some(set) =>
          val nextForward = set.foldLeft(state.forward)(_ - _)
          (set.size.toLong, State(nextForward, state.reverse - instanceId))
    }

  /** Clear all data (for testing). */
  def clear: UIO[Unit] =
    stateRef.set(State.empty)
end InMemoryInstanceIndex

object InMemoryInstanceIndex:

  private[stores] final case class State[Id](
      forward: Map[Alias, Id],
      reverse: Map[Id, Set[Alias]],
  )

  private[stores] object State:
    def empty[Id]: State[Id] = State(Map.empty, Map.empty)

  def make[Id]: UIO[InMemoryInstanceIndex[Id]] =
    Ref.make(State.empty[Id]).map(new InMemoryInstanceIndex(_))

  def layer[Id: Tag]: ULayer[InstanceIndex[Id]] =
    ZLayer.fromZIO(make[Id])
end InMemoryInstanceIndex
