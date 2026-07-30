package mechanoid.stores

import zio.*
import zio.stream.*
import mechanoid.core.*
import mechanoid.persistence.*
import scala.annotation.unused

/** ZIO Ref-based in-memory EventStore implementation with optional bounded storage.
  *
  * Thread-safe via ZIO Refs. Suitable for testing and simple single-process deployments. For distributed deployments,
  * use a database-backed implementation like PostgresEventStore.
  *
  * ==Bounded vs Unbounded==
  *
  * By default, the store is '''bounded''' to prevent unbounded memory growth:
  *   - `make()` creates a bounded store (default 1000 events per instance)
  *   - When the limit is reached, oldest events are evicted (FIFO)
  *   - Sequence numbers remain monotonically increasing even after eviction
  *
  * For testing scenarios that need full event history, use `makeUnbounded()`.
  *
  * ==Usage==
  * {{{
  * // Bounded store (production use)
  * for
  *   store <- InMemoryEventStore.make[String, MyState, MyEvent]()
  *   _     <- store.append("instance-1", event, 0L)
  * yield ()
  *
  * // Custom bound
  * store <- InMemoryEventStore.make[String, MyState, MyEvent](maxEventsPerInstance = 100)
  *
  * // Unbounded (testing only)
  * store <- InMemoryEventStore.makeUnbounded[String, MyState, MyEvent]
  * }}}
  */
final class InMemoryEventStore[Id, S, E] private (
    eventsRef: Ref[Map[Id, Chunk[StoredEvent[Id, E]]]],
    snapshotsRef: Ref[Map[Id, FSMSnapshot[Id, S]]],
    seqNrRef: Ref[Map[Id, Long]],
    maxEventsPerInstance: Option[Int],
) extends EventStore[Id, S, E]:

  override def append(
      instanceId: Id,
      event: E,
      @unused expectedSeqNr: Long,
  ): ZIO[Any, MechanoidError, Long] =
    for
      now   <- Clock.instant
      seqNr <- seqNrRef.modify { seqNrs =>
        val current  = seqNrs.getOrElse(instanceId, 0L)
        val newSeqNr = current + 1
        (newSeqNr, seqNrs + (instanceId -> newSeqNr))
      }
      stored = StoredEvent(instanceId, seqNr, event, now)
      _ <- eventsRef.update { events =>
        val current = events.getOrElse(instanceId, Chunk.empty)
        val withNew = current :+ stored
        // Apply bounded eviction if configured
        val bounded = maxEventsPerInstance match
          case Some(max) if withNew.size > max => withNew.drop(1)
          case _                               => withNew
        events + (instanceId -> bounded)
      }
    yield seqNr

  override def loadEvents(instanceId: Id): ZStream[Any, MechanoidError, StoredEvent[Id, E]] =
    ZStream.unwrap(
      eventsRef.get.map { events =>
        ZStream.fromIterable(events.getOrElse(instanceId, Chunk.empty))
      }
    )

  override def loadSnapshot(instanceId: Id): ZIO[Any, MechanoidError, Option[FSMSnapshot[Id, S]]] =
    snapshotsRef.get.map(_.get(instanceId))

  override def saveSnapshot(snapshot: FSMSnapshot[Id, S]): ZIO[Any, MechanoidError, Unit] =
    snapshotsRef.update(_ + (snapshot.instanceId -> snapshot))

  override def highestSequenceNr(instanceId: Id): ZIO[Any, MechanoidError, Long] =
    seqNrRef.get.map(_.getOrElse(instanceId, 0L))

  override def deleteEventsTo(instanceId: Id, toSequenceNr: Long): ZIO[Any, MechanoidError, Unit] =
    for
      remaining <- eventsRef.modify { events =>
        val current = events.getOrElse(instanceId, Chunk.empty)
        val kept    = current.filter(_.sequenceNr > toSequenceNr)
        val nextMap = if kept.isEmpty then events - instanceId else events + (instanceId -> kept)
        (kept, nextMap)
      }
      _ <- seqNrRef.update { seqNrs =>
        val newHighest = remaining.map(_.sequenceNr).maxOption.getOrElse(0L)
        if newHighest == 0L then seqNrs - instanceId
        else seqNrs + (instanceId -> newHighest)
      }
    yield ()

  /** Clear all data (for testing). */
  def clear: UIO[Unit] =
    eventsRef.set(Map.empty) *> snapshotsRef.set(Map.empty) *> seqNrRef.set(Map.empty)
end InMemoryEventStore

object InMemoryEventStore:

  /** Configuration for in-memory event store.
    *
    * @param maxEventsPerInstance
    *   Maximum events to keep per instance. When exceeded, oldest events are evicted (FIFO). Default is 1000.
    */
  case class Config(maxEventsPerInstance: Int = 1000)

  /** Default configuration with bounded storage (1000 events per instance). */
  val defaultConfig: Config = Config()

  // ============================================
  // Direct creation methods
  // ============================================

  /** Create a bounded in-memory event store.
    *
    * Events are evicted FIFO when the per-instance limit is reached. This prevents unbounded memory growth for
    * long-running FSMs.
    *
    * @param maxEventsPerInstance
    *   Maximum events to keep per instance (default 1000)
    */
  def make[Id, S, E](maxEventsPerInstance: Int = 1000): UIO[InMemoryEventStore[Id, S, E]] =
    for
      eventsRef    <- Ref.make(Map.empty[Id, Chunk[StoredEvent[Id, E]]])
      snapshotsRef <- Ref.make(Map.empty[Id, FSMSnapshot[Id, S]])
      seqNrRef     <- Ref.make(Map.empty[Id, Long])
    yield new InMemoryEventStore(eventsRef, snapshotsRef, seqNrRef, Some(maxEventsPerInstance))

  /** Create an unbounded in-memory event store.
    *
    * '''Warning''': This stores all events forever and can lead to unbounded memory growth. Use only for testing
    * scenarios that need full event history inspection.
    */
  def makeUnbounded[Id, S, E]: UIO[InMemoryEventStore[Id, S, E]] =
    for
      eventsRef    <- Ref.make(Map.empty[Id, Chunk[StoredEvent[Id, E]]])
      snapshotsRef <- Ref.make(Map.empty[Id, FSMSnapshot[Id, S]])
      seqNrRef     <- Ref.make(Map.empty[Id, Long])
    yield new InMemoryEventStore(eventsRef, snapshotsRef, seqNrRef, None)

  // ============================================
  // Layer creation methods
  // ============================================

  /** Create a layer with default bounded config (1000 events per instance).
    *
    * Usage:
    * {{{
    * val storeLayer = InMemoryEventStore.layer[String, MyState, MyEvent]
    * FSMRuntime("id", machine, initial)
    *   .provideSomeLayer(storeLayer ++ timeoutLayer ++ lockingLayer)
    * }}}
    */
  def layer[Id: Tag, S: Tag, E: Tag]: ULayer[EventStore[Id, S, E]] =
    ZLayer.fromZIO(make[Id, S, E]())

  /** Create a layer with custom configuration.
    *
    * Usage:
    * {{{
    * val storeLayer = InMemoryEventStore.layer[String, MyState, MyEvent](
    *   InMemoryEventStore.Config(maxEventsPerInstance = 100)
    * )
    * }}}
    */
  def layer[Id: Tag, S: Tag, E: Tag](config: Config): ULayer[EventStore[Id, S, E]] =
    ZLayer.fromZIO(make[Id, S, E](config.maxEventsPerInstance))

  /** Create an unbounded layer for testing.
    *
    * '''Warning''': This stores all events forever and can lead to unbounded memory growth. Use only for testing
    * scenarios that need full event history inspection.
    *
    * Usage:
    * {{{
    * val testStoreLayer = InMemoryEventStore.unboundedLayer[String, MyState, MyEvent]
    * }}}
    */
  def unboundedLayer[Id: Tag, S: Tag, E: Tag]: ULayer[EventStore[Id, S, E]] =
    ZLayer.fromZIO(makeUnbounded[Id, S, E])

end InMemoryEventStore
