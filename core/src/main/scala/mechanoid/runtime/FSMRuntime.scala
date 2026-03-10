package mechanoid.runtime

import zio.*
import mechanoid.core.*
import mechanoid.machine.{Machine, EntryEffect, ProducingEffect}
import mechanoid.persistence.{EventStore, FSMSnapshot, StoredEvent}
import mechanoid.stores.InMemoryEventStore
import mechanoid.runtime.timeout.{TimeoutStrategy, FiberTimeoutStrategy}
import mechanoid.runtime.locking.{LockingStrategy, OptimisticLockingStrategy}
import java.time.Instant
import scala.annotation.nowarn

/** The runtime interface for an active FSM.
  *
  * All errors are returned as `MechanoidError`. User errors from lifecycle actions are wrapped in `ActionFailedError`.
  *
  * @tparam Id
  *   The FSM instance identifier type (use `Unit` for anonymous/ephemeral FSMs)
  * @tparam S
  *   The state type
  * @tparam E
  *   The event type
  */
trait FSMRuntime[Id, S, E]:

  /** The FSM instance identifier. */
  def instanceId: Id

  /** Send an event to the FSM and get the transition outcome.
    *
    * Returns the outcome of processing the event:
    *   - result: The transition result (Stay, Goto, Stop)
    *
    * Per-transition effects (`.onEntry` and `.producing`) are executed automatically:
    *   - Entry effects run synchronously before `send` returns
    *   - Producing effects fork asynchronously and send their produced events back to the FSM
    *
    * If no transition is defined for the current state and event, returns an InvalidTransitionError.
    */
  def send(event: E): ZIO[Any, MechanoidError, TransitionOutcome[S]]

  /** Get the current state of the FSM. */
  def currentState: UIO[S]

  /** Get the full FSM state including metadata. */
  def state: UIO[FSMState[S]]

  /** Get the history of previous states (most recent first). */
  def history: UIO[List[S]]

  /** Get the last persisted sequence number.
    *
    * Useful for implementing snapshot strategies (e.g., snapshot every N events).
    */
  def lastSequenceNr: UIO[Long]

  /** Take a snapshot of the current state.
    *
    * Snapshots allow faster recovery by avoiding full event replay. On next startup, only events after this snapshot
    * are replayed.
    *
    * '''This is NOT called automatically.''' You decide when to snapshot:
    * {{{
    * // After every N events
    * seqNr <- fsm.lastSequenceNr
    * _     <- ZIO.when(seqNr % 100 == 0)(fsm.saveSnapshot)
    *
    * // Periodically
    * fsm.saveSnapshot.repeat(Schedule.fixed(5.minutes)).forkDaemon
    *
    * // On specific states
    * _ <- ZIO.when(fsm.currentState == FinalState)(fsm.saveSnapshot)
    * }}}
    *
    * After snapshotting, you may optionally delete old events via `EventStore.deleteEventsTo` to reclaim storage.
    */
  def saveSnapshot: ZIO[Any, MechanoidError, Unit]

  /** Stop the FSM gracefully. */
  def stop: UIO[Unit]

  /** Stop the FSM with a reason. */
  def stop(reason: String): UIO[Unit]

  /** Check if the FSM is currently running. */
  def isRunning: UIO[Boolean]

  /** Get the timeout configuration for a given state.
    *
    * Returns `Some((duration, event))` if the state has a timeout configured, `None` otherwise. Used by
    * [[mechanoid.runtime.aspects.DurableTimeoutRuntime]] to manage durable timeouts.
    *
    * @param state
    *   The state to check
    * @return
    *   Timeout duration and event if configured
    */
  def timeoutConfigForState(state: S): Option[(Duration, E)]

  /** Access the underlying Machine definition.
    *
    * Provides access to state/event enums, timeout configurations, and transition metadata. Used by TimeoutSweeper to
    * resolve event hashes back to typed events.
    */
  def machine: Machine[S, E]
end FSMRuntime

object FSMRuntime:

  // ============================================
  // Simple In-Memory FSM (no persistence, no Id)
  // ============================================

  /** Create a simple in-memory FSM runtime.
    *
    * This is the simplest way to use an FSM - no persistence, no distributed features. State is held in memory and lost
    * when the scope closes. Events are stored in a bounded buffer (default 1000) to prevent unbounded memory growth.
    *
    * For custom event buffer size or unbounded storage, use [[InMemoryEventStore.layer]] with the layer-based
    * [[FSMRuntime.apply]] instead.
    *
    * {{{
    * val machine = Machine(assembly[TrafficLight, TrafficEvent](
    *   Red via Timer to Green,
    *   Green via Timer to Yellow,
    *   Yellow via Timer to Red,
    * ))
    *
    * val program = ZIO.scoped {
    *   for
    *     fsm   <- machine.start(Red)
    *     _     <- fsm.send(Timer)
    *     state <- fsm.currentState  // Green
    *   yield state
    * }
    * }}}
    *
    * @param machine
    *   The Machine definition
    * @param initial
    *   The initial state
    */
  def make[S, E](
      machine: Machine[S, E],
      initial: S,
  ): ZIO[Scope, MechanoidError, FSMRuntime[Unit, S, E]] =
    for
      eventStore      <- InMemoryEventStore.make[Unit, S, E]()
      timeoutStrategy <- FiberTimeoutStrategy.make[Unit]
      lockingStrategy = OptimisticLockingStrategy.make[Unit]
      runtime <- ZIO.acquireRelease(
        createRuntime((), machine, initial, eventStore, timeoutStrategy, lockingStrategy)
      )(_.stop)
    yield runtime

  // ============================================
  // Persistent FSM with EventStore from environment
  // ============================================

  /** Create a persistent FSM runtime, pulling dependencies from the environment.
    *
    * Provide the EventStore, TimeoutStrategy, and LockingStrategy via ZLayer:
    *
    * {{{
    * // Create the store layer - no type params needed!
    * val storeLayer = PostgresEventStore.layer
    *
    * // Use the FSM with dependencies from the environment
    * val program = ZIO.scoped {
    *   for
    *     fsm <- FSMRuntime(orderId, orderMachine, Pending)
    *     _   <- fsm.send(Pay)
    *   yield ()
    * }.provide(
    *   storeLayer,
    *   transactorLayer,
    *   TimeoutStrategy.fiber[OrderId],    // or TimeoutStrategy.durable
    *   LockingStrategy.optimistic[OrderId] // or LockingStrategy.distributed
    * )
    * }}}
    *
    * This will:
    *   1. Load the latest snapshot (if any)
    *   2. Replay events since the snapshot to rebuild state
    *   3. Continue processing new events, persisting each one
    *
    * ==JsonCodec Requirement==
    *
    * State and Event types must have `JsonCodec` instances. If your types `derives Finite`, you get `JsonCodec`
    * automatically via the package-level given (just `import mechanoid.*`).
    *
    * ==Timeout Strategy==
    *
    * You must provide a [[TimeoutStrategy]] to handle state timeouts:
    *   - [[timeout.FiberTimeoutStrategy]]: In-memory, fast, doesn't survive node failures
    *   - [[timeout.DurableTimeoutStrategy]]: Persists to TimeoutStore, survives failures
    *
    * ==Locking Strategy==
    *
    * You must provide a [[LockingStrategy]] to handle concurrent access:
    *   - [[locking.OptimisticLockingStrategy]]: No explicit locks, relies on EventStore conflict detection
    *   - [[locking.DistributedLockingStrategy]]: Acquires exclusive locks, prevents conflicts
    *
    * @param id
    *   The FSM instance identifier
    * @param machine
    *   The Machine definition
    * @param initialState
    *   The initial state for new instances
    */
  @nowarn("msg=unused implicit parameter")
  def apply[Id: Tag, S, E](
      id: Id,
      machine: Machine[S, E],
      initialState: S,
  )(using
      Tag[EventStore[Id, S, E]],
      Tag[TimeoutStrategy[Id]],
      Tag[LockingStrategy[Id]],
  ): ZIO[
    Scope & EventStore[Id, S, E] & TimeoutStrategy[Id] & LockingStrategy[Id],
    MechanoidError,
    FSMRuntime[Id, S, E],
  ] =
    for
      store           <- ZIO.service[EventStore[Id, S, E]]
      timeoutStrategy <- ZIO.service[TimeoutStrategy[Id]]
      lockingStrategy <- ZIO.service[LockingStrategy[Id]]
      runtime         <- ZIO.acquireRelease(
        createRuntime(id, machine, initialState, store, timeoutStrategy, lockingStrategy)
      )(_.stop)
    yield runtime

  // ============================================
  // Implementation
  // ============================================

  /** Create and initialize an FSM runtime.
    *
    * This is the core factory method used by all the public factory methods above.
    *
    * @param id
    *   FSM instance identifier
    * @param machine
    *   Machine definition
    * @param initialState
    *   Initial state for new instances
    * @param store
    *   Event store for persistence
    * @param timeoutStrategy
    *   Strategy for handling state timeouts
    * @param lockingStrategy
    *   Strategy for handling concurrent access
    */
  private def createRuntime[Id, S, E](
      id: Id,
      machine: Machine[S, E],
      initialState: S,
      store: EventStore[Id, S, E],
      timeoutStrategy: TimeoutStrategy[Id],
      lockingStrategy: LockingStrategy[Id],
  ): ZIO[Any, MechanoidError, FSMRuntimeImpl[Id, S, E]] =
    for
      // Load snapshot and events to rebuild state
      snapshot <- store.loadSnapshot(id)
      startState = snapshot.map(_.state).getOrElse(initialState)
      startSeqNr = snapshot.map(_.sequenceNr).getOrElse(0L)

      // Collect events to replay
      events <- store.loadEventsFrom(id, startSeqNr).runCollect

      // Rebuild state by applying events
      rebuiltState <- rebuildState(machine, startState, events.toList)

      // Initialize runtime state
      stateRef   <- Ref.make(rebuiltState)
      seqNrRef   <- Ref.make(events.lastOption.map(_.sequenceNr).getOrElse(startSeqNr))
      runningRef <- Ref.make(true)

      // Create self-reference for timeout handling
      runtimeRef <- Ref.make[Option[FSMRuntimeImpl[Id, S, E]]](None)

      sendSelf: (E => ZIO[Any, MechanoidError, TransitionOutcome[S]]) =
        (event: E) =>
          runtimeRef.get.flatMap(
            _.map(_.sendInternal(event))
              .getOrElse(ZIO.fail(FSMStoppedError(Some("Runtime not initialized"))))
          )

      runtime = new FSMRuntimeImpl(
        id,
        machine,
        store,
        timeoutStrategy,
        lockingStrategy,
        stateRef,
        seqNrRef,
        runningRef,
        sendSelf,
      )

      _ <- runtimeRef.set(Some(runtime))

      // Start timeout for current state if configured
      _ <- runtime.startTimeout(rebuiltState.current)
    yield runtime

  /** Rebuild FSM state by replaying events.
    *
    * This applies each event in sequence to reconstruct the current state. Transition actions ARE executed to determine
    * the target state. Entry/exit actions are NOT executed during replay.
    *
    * @throws EventReplayError
    *   if an event doesn't match the current FSM definition
    */
  private def rebuildState[S, E](
      machine: Machine[S, E],
      startState: S,
      events: List[StoredEvent[?, E]],
  ): ZIO[Any, EventReplayError[S, E], FSMState[S]] =
    ZIO.foldLeft(events)(FSMState.initial(startState)) { (fsmState, stored) =>
      val currentCaseHash = machine.stateEnum.caseHash(fsmState.current)
      val eventCaseHash   = machine.eventEnum.caseHash(stored.event)
      machine.transitions.get((currentCaseHash, eventCaseHash)) match
        case Some(transition) =>
          // Execute the transition action to get the result
          // During replay, errors are ignored (events were already validated when stored)
          transition
            .action(fsmState.current, stored.event)
            .catchAll(_ => ZIO.succeed(TransitionResult.Stay))
            .map {
              case TransitionResult.Goto(newState) =>
                fsmState.transitionTo(newState, stored.timestamp)
              case _ => fsmState
            }
        case None =>
          // Event doesn't match current FSM definition - fail explicitly
          ZIO.fail(EventReplayError(fsmState.current, stored.event, stored.sequenceNr))
      end match
    }
end FSMRuntime

/** Internal implementation of FSMRuntime.
  *
  * All FSM runtimes (in-memory and persistent) use this same implementation, differing only in the EventStore,
  * TimeoutStrategy, and LockingStrategy provided.
  */
private[mechanoid] final class FSMRuntimeImpl[Id, S, E](
    val instanceId: Id,
    val machine: Machine[S, E],
    store: EventStore[Id, S, E],
    timeoutStrategy: TimeoutStrategy[Id],
    lockingStrategy: LockingStrategy[Id],
    stateRef: Ref[FSMState[S]],
    seqNrRef: Ref[Long],
    runningRef: Ref[Boolean],
    sendSelf: E => ZIO[Any, MechanoidError, TransitionOutcome[S]],
) extends FSMRuntime[Id, S, E]:

  override def send(event: E): ZIO[Any, MechanoidError, TransitionOutcome[S]] =
    sendSelf(event) // Direct call, no .timed wrapping

  private[mechanoid] def sendInternal(
      event: E
  ): ZIO[Any, MechanoidError, TransitionOutcome[S]] =
    lockingStrategy.withLock(
      instanceId,
      for
        running <- runningRef.get
        result  <-
          if !running then ZIO.succeed(TransitionOutcome(TransitionResult.Stop(Some("FSM stopped"))))
          else processEvent(event)
      yield result,
    )

  private def processEvent(
      event: E
  ): ZIO[Any, MechanoidError, TransitionOutcome[S]] =
    for
      fsmState <- stateRef.get
      currentState    = fsmState.current
      currentCaseHash = machine.stateEnum.caseHash(currentState)
      eventCaseHash   = machine.eventEnum.caseHash(event)
      transition <- ZIO
        .fromOption(machine.transitions.get((currentCaseHash, eventCaseHash)))
        .orElseFail(InvalidTransitionError(currentState, event))
      outcome <- executeTransition(fsmState, event, transition, currentCaseHash, eventCaseHash)
    yield outcome

  private def executeTransition(
      fsmState: FSMState[S],
      event: E,
      transition: Transition[S, E, S],
      stateHash: Int,
      eventHash: Int,
  ): ZIO[Any, MechanoidError, TransitionOutcome[S]] =
    for
      // Execute the transition action FIRST
      // If it fails (e.g., external service call), the event is NOT persisted
      result <- transition.action(fsmState.current, event)

      // Only persist after successful action execution
      // Use optimistic locking to detect concurrent modifications
      currentSeqNr <- seqNrRef.get
      seqNr        <- store.append(instanceId, event, currentSeqNr)
      _            <- seqNrRef.set(seqNr)

      // Determine target state for effects
      targetState = result match
        case TransitionResult.Goto(s) => s
        case _                        => fsmState.current

      // Update state
      _ <- handleTransitionResult(fsmState, event, result)

      // Run per-transition entry effect (sync)
      _ <- runTransitionEntryEffect(stateHash, eventHash, event, targetState)

      // Fork per-transition producing effect (async) - produces an event that is sent back to FSM
      _ <- forkProducingEffect(stateHash, eventHash, event, targetState)
    yield TransitionOutcome(result)

  /** Run per-transition entry effect synchronously.
    *
    * This is different from state lifecycle `onEntry` - it runs for this specific (state, event) transition only.
    */
  private def runTransitionEntryEffect(
      stateHash: Int,
      eventHash: Int,
      event: E,
      targetState: S,
  ): ZIO[Any, MechanoidError, Unit] =
    machine.entryEffects.get((stateHash, eventHash)) match
      case Some(effect) =>
        effect
          .run(event, targetState)
          .catchAll { e =>
            ZIO.fail(ActionFailedError("entry effect", e))
          }
          .unit
      case None => ZIO.unit

  /** Fork producing effect asynchronously.
    *
    * The effect runs in the background and produces an event that is automatically sent back to the FSM.
    */
  private def forkProducingEffect(
      stateHash: Int,
      eventHash: Int,
      event: E,
      targetState: S,
  ): ZIO[Any, Nothing, Unit] =
    machine.producingEffects.get((stateHash, eventHash)) match
      case Some(producingEffect) =>
        val effect = producingEffect
          .run(event, targetState)
          .flatMap { producedEvent =>
            // Send the produced event back to the FSM
            send(producedEvent).ignore
          }
          .catchAll { e =>
            // Log error but don't fail - producing effects are fire-and-forget
            // Users should use timeouts as fallback for failure handling
            ZIO.logError(s"Producing effect failed: $e")
          }
        effect.forkDaemon.unit
      case None => ZIO.unit

  private def handleTransitionResult(
      fsmState: FSMState[S],
      event: E,
      result: TransitionResult[S],
  ): ZIO[Any, MechanoidError, Unit] =
    result match
      case TransitionResult.Goto(newState) =>
        for
          // Cancel any pending timeout for the current state
          _ <- cancelTimeout
          // Run exit effect for current state
          _ <- runStateExitEffect(event, fsmState.current)
          // Update state
          now = Instant.now()
          _ <- stateRef.update(_.transitionTo(newState, now))
          // Run entry effect for new state
          _ <- runStateEntryEffect(event, newState)
          // Start timeout for new state if configured
          _ <- startTimeout(newState)
        yield ()

      case TransitionResult.Stay =>
        ZIO.unit

      case TransitionResult.Stop(_) =>
        for
          _ <- cancelTimeout
          _ <- runStateExitEffect(event, fsmState.current)
          _ <- runningRef.set(false)
        yield ()

  private def runStateEntryEffect(event: E, state: S): ZIO[Any, MechanoidError, Unit] =
    machine.stateEntryEffects.get(machine.stateEnum.caseHash(state)) match
      case Some(effect) =>
        effect(event, state).catchAll(e => ZIO.fail(ActionFailedError("state entry effect", e))).unit
      case None => ZIO.unit

  private def runStateExitEffect(event: E, state: S): ZIO[Any, MechanoidError, Unit] =
    machine.stateExitEffects.get(machine.stateEnum.caseHash(state)) match
      case Some(effect) =>
        effect(event, state).catchAll(e => ZIO.fail(ActionFailedError("state exit effect", e))).unit
      case None => ZIO.unit

  /** Cancel any pending timeout for this FSM instance.
    *
    * Delegates to the [[TimeoutStrategy]] to handle cancellation.
    */
  private def cancelTimeout: ZIO[Any, Nothing, Unit] =
    timeoutStrategy.cancel(instanceId)

  /** Start a timeout for the given state.
    *
    * Delegates to the [[TimeoutStrategy]] to schedule the timeout. The callback checks that the FSM is still in the
    * same state before firing the timeout event.
    *
    * For durable timeouts, the `stateHash` and `sequenceNr` are persisted to enable validation before firing. This
    * prevents stale timeouts from firing after the FSM has transitioned or re-entered the same state.
    *
    * @param state
    *   The state to start a timeout for
    */
  private[mechanoid] def startTimeout(state: S): ZIO[Any, Nothing, Unit] =
    val stateHash = machine.stateEnum.caseHash(state)
    // Get both duration and user-defined timeout event for this state
    val timeoutConfig = for
      duration <- machine.timeouts.get(stateHash)
      event    <- machine.timeoutEvents.get(stateHash)
    yield (duration, event)

    ZIO.foreachDiscard(timeoutConfig) { case (duration, timeoutEvent) =>
      for
        // Capture current sequence number as generation counter for this visit to the state
        seqNr <- seqNrRef.get
        // Create callback that fires timeout event if still in same state (for FiberTimeoutStrategy)
        onTimeout: UIO[Unit] = stateRef.get.flatMap { currentFsmState =>
          // Compare by caseHash (shape) not exact value - timeout fires if still in same state shape
          ZIO
            .when(machine.stateEnum.caseHash(currentFsmState.current) == stateHash)(
              send(timeoutEvent).ignore
            )
            .unit
        }
        // Pass stateHash and sequenceNr for durable timeout validation
        _ <- timeoutStrategy.schedule(instanceId, stateHash, seqNr, duration, onTimeout)
      yield ()
    }
  end startTimeout

  override def currentState: UIO[S] = stateRef.get.map(_.current)

  override def state: UIO[FSMState[S]] = stateRef.get

  override def history: UIO[List[S]] = stateRef.get.map(_.history)

  override def lastSequenceNr: UIO[Long] = seqNrRef.get

  override def stop: UIO[Unit] = runningRef.set(false)

  override def stop(reason: String): UIO[Unit] = runningRef.set(false)

  override def isRunning: UIO[Boolean] = runningRef.get

  override def timeoutConfigForState(state: S): Option[(Duration, E)] =
    val stateCaseHash = machine.stateEnum.caseHash(state)
    for
      duration <- machine.timeouts.get(stateCaseHash)
      event    <- machine.timeoutEvents.get(stateCaseHash)
    yield (duration, event)

  override def saveSnapshot: ZIO[Any, MechanoidError, Unit] =
    for
      fsmState <- stateRef.get
      seqNr    <- seqNrRef.get
      snapshot = FSMSnapshot(
        instanceId = instanceId,
        state = fsmState.current,
        sequenceNr = seqNr,
        timestamp = Instant.now(),
      )
      _ <- store.saveSnapshot(snapshot)
    yield ()
end FSMRuntimeImpl
