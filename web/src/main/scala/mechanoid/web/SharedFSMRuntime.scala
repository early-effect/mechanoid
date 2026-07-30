package mechanoid.web

import zio.*
import zio.json.*
import mechanoid.*
import mechanoid.machine.Machine
import mechanoid.persistence.*
import mechanoid.persistence.lock.FSMInstanceLock
import mechanoid.persistence.timeout.TimeoutStore
import mechanoid.runtime.locking.LockingStrategy
import mechanoid.runtime.timeout.TimeoutStrategy

/** Helper wiring IndexedDB stores, durable timeouts, instance lock, and BroadcastChannel peer notify.
  *
  * Peer tabs stay aligned the same way server nodes do: reconstruct [[FSMRuntime]] from the EventStore (no live
  * catch-up API).
  */
object SharedFSMRuntime:

  /** Open shared IndexedDB stores + tab synchronizer for a given DB / channel name. */
  def stores[S: JsonCodec: Tag, E: JsonCodec: Tag](
      dbName: String = "mechanoid",
      channelName: String = "mechanoid-sync",
  ): ZIO[Scope, MechanoidError, SharedStores[S, E]] =
    for
      sync <- TabSynchronizer.make(channelName)
      _    <- ZIO.addFinalizer(sync.stop)
      notify: (String => UIO[Unit]) = id => sync.publish(id)
      events   <- IndexedDbEventStore.make[S, E](dbName, notify)
      timeouts <- IndexedDbTimeoutStore.make(dbName, notify)
      locks    <- IndexedDbInstanceLock.make(dbName)
    yield SharedStores(events, timeouts, locks, sync)

  /** Start an FSM and reconstruct it when a peer writes this instance id. */
  def start[S: Finite: Tag, E: Finite: Tag](
      instanceId: String,
      machine: Machine[S, E],
      initial: S,
      stores: SharedStores[S, E],
      onState: S => UIO[Unit] = (_: S) => ZIO.unit,
  ): ZIO[Scope, MechanoidError, FSMRuntime[String, S, E]] =
    for
      parent     <- ZIO.scope
      childRef   <- Ref.make(Option.empty[Scope.Closeable])
      runtimeRef <- Ref.make(Option.empty[FSMRuntime[String, S, E]])
      open =
        for
          prevChild <- childRef.get
          _         <- ZIO.foreachDiscard(prevChild)(_.close(Exit.unit))
          child     <- Scope.make
          _         <- parent.addFinalizerExit(ex => child.close(ex))
          _         <- childRef.set(Some(child))
          runtime   <- child.extend(
            FSMRuntime(instanceId, machine, initial).provideSome[Scope](
              ZLayer.succeed[EventStore[String, S, E]](stores.events),
              ZLayer.succeed[TimeoutStore[String]](stores.timeouts),
              TimeoutStrategy.durable[String],
              ZLayer.succeed[FSMInstanceLock[String]](stores.locks),
              LockingStrategy.distributed[String],
            )
          )
        yield runtime
      first <- open
      _     <- runtimeRef.set(Some(first))
      _     <- first.currentState.flatMap(onState)
      _     <- stores.sync.listen { id =>
        ZIO
          .when(id == instanceId) {
            (for
              next <- open
              _    <- runtimeRef.set(Some(next))
              _    <- next.currentState.flatMap(onState)
            yield ()).ignore
          }
          .unit
      }
    yield DelegatingFSMRuntime(instanceId, machine, runtimeRef)

  final case class SharedStores[S, E](
      events: EventStore[String, S, E],
      timeouts: TimeoutStore[String],
      locks: FSMInstanceLock[String],
      sync: TabSynchronizer,
  )

  /** Forwards to the current runtime in [[ref]] (replaced on peer reconstruct). */
  private final class DelegatingFSMRuntime[S, E](
      val instanceId: String,
      val machine: Machine[S, E],
      ref: Ref[Option[FSMRuntime[String, S, E]]],
  ) extends FSMRuntime[String, S, E]:

    private def runtime: UIO[FSMRuntime[String, S, E]] =
      ref.get.flatMap {
        case Some(r) => ZIO.succeed(r)
        case None    => ZIO.die(new IllegalStateException(s"FSMRuntime not started for $instanceId"))
      }

    override def send(event: E): ZIO[Any, MechanoidError, TransitionOutcome[S]] =
      runtime.flatMap(_.send(event))

    override def currentState: UIO[S] = runtime.flatMap(_.currentState)

    override def state: UIO[FSMState[S]] = runtime.flatMap(_.state)

    override def history: UIO[List[S]] = runtime.flatMap(_.history)

    override def lastSequenceNr: UIO[Long] = runtime.flatMap(_.lastSequenceNr)

    override def saveSnapshot: ZIO[Any, MechanoidError, Unit] =
      runtime.flatMap(_.saveSnapshot)

    override def stop: UIO[Unit] = runtime.flatMap(_.stop)

    override def stop(reason: String): UIO[Unit] = runtime.flatMap(_.stop(reason))

    override def isRunning: UIO[Boolean] = runtime.flatMap(_.isRunning)

    override def timeoutConfigForState(state: S): Option[(Duration, E)] =
      val stateCaseHash = machine.stateEnum.caseHash(state)
      for
        duration <- machine.timeouts.get(stateCaseHash)
        event    <- machine.timeoutEvents.get(stateCaseHash)
      yield (duration, event)
  end DelegatingFSMRuntime
end SharedFSMRuntime
