package mechanoid.persistence.lock

import zio.*
import mechanoid.core.*
import mechanoid.runtime.FSMRuntime

/** A wrapper that adds distributed locking to an FSMRuntime.
  *
  * This ensures exactly-once transition semantics by acquiring a lock before processing each event. If another node is
  * processing the same FSM instance, the operation waits (up to timeout) or fails.
  *
  * ==Why Use Locking?==
  *
  * Without locking, concurrent event processing relies on optimistic locking which detects conflicts ''after'' the
  * fact. With distributed locking:
  *
  *   - Only one node processes events for an FSM at a time
  *   - No wasted work from rejected writes
  *   - Guaranteed exactly-once delivery per event
  *
  * ==Node Failure Resilience==
  *
  * Locks are lease-based and automatically expire. If a node crashes:
  *   1. Other nodes wait for the lock to expire
  *   2. Lock expires after `lockDuration`
  *   3. Another node acquires the lock and continues processing
  *
  * ==Zombie Node Protection==
  *
  * Even if a paused node wakes up after its lock expired:
  *   - The lock is validated before each operation (if configured)
  *   - EventStore's optimistic locking provides a final safety net
  *
  * ==Usage==
  *
  * {{{
  * val program = ZIO.scoped {
  *   for
  *     fsm <- FSMRuntime.withLocking(orderId, definition, Pending)
  *     _   <- fsm.send(Pay)  // Lock acquired automatically around this call
  *   yield ()
  * }.provide(eventStoreLayer, lockLayer)
  * }}}
  */
final class LockedFSMRuntime[Id, S, E] private[lock] (
    underlying: FSMRuntime[Id, S, E],
    lock: FSMInstanceLock[Id],
    config: LockConfig,
) extends FSMRuntime[Id, S, E]:

  override def instanceId: Id = underlying.instanceId

  override def send(event: E): ZIO[Any, MechanoidError, TransitionOutcome[S]] =
    lock
      .withLock(instanceId, config.nodeId, config.lockDuration, Some(config.acquireTimeout)) {
        // Optionally validate lock is still held before proceeding
        validateLockIfConfigured *>
          underlying.send(event)
      }
      .mapError {
        case e: LockError      => LockingError(e)
        case e: MechanoidError => e
      }

  /** Validate that we still hold the lock (if configured). */
  private def validateLockIfConfigured: ZIO[Any, LockError, Unit] =
    if config.validateBeforeOperation then
      Clock.instant.flatMap { now =>
        lock
          .get(instanceId, now)
          .flatMap {
            case Some(token) if token.nodeId == config.nodeId =>
              ZIO.unit
            case Some(token) =>
              ZIO.fail(LockError.LockBusy(instanceId.toString, token.nodeId, token.expiresAt))
            case None =>
              // Lock was released/expired - this shouldn't happen inside withLock
              // but check anyway for safety
              ZIO.fail(
                LockError.LockTimeout(instanceId.toString, config.lockDuration)
              )
          }
          .mapError {
            case e: LockError      => e
            case e: MechanoidError =>
              LockError.LockAcquisitionFailed(instanceId.toString, new RuntimeException(e.toString))
          }
      }
    else ZIO.unit

  override def currentState: UIO[S] = underlying.currentState

  override def state: UIO[FSMState[S]] = underlying.state

  override def history: UIO[List[S]] = underlying.history

  override def lastSequenceNr: UIO[Long] = underlying.lastSequenceNr

  override def stop: UIO[Unit] = underlying.stop

  override def stop(reason: String): UIO[Unit] = underlying.stop(reason)

  override def isRunning: UIO[Boolean] = underlying.isRunning

  override def timeoutConfigForState(state: S): Option[(Duration, E)] =
    underlying.timeoutConfigForState(state)

  override def machine = underlying.machine

  override def saveSnapshot: ZIO[Any, MechanoidError, Unit] =
    // Snapshots don't need locking - they're read-only from the FSM's perspective
    underlying.saveSnapshot

  /** Execute multiple FSM transitions atomically while holding a single lock with heartbeat renewal.
    *
    * Use this for orchestration logic that requires multiple transitions without interleaving from other nodes. The
    * heartbeat keeps the lock alive during the orchestration.
    *
    * ==When to Use==
    *
    *   - Conditional transitions based on intermediate state
    *   - Saga-like patterns where multiple events form one logical operation
    *   - Reading state between transitions for branching logic
    *
    * ==When NOT to Use (Anti-Patterns)==
    *
    * '''Do NOT use this for long-running work.''' That defeats the purpose of the lock (which should be held briefly).
    *
    * '''Bad - Don't do this:'''
    * {{{
    * fsm.withAtomicTransitions() { ctx =>
    *   for
    *     _ <- ctx.send(StartProcessing)
    *     _ <- callExternalPaymentAPI()  // ❌ WRONG - long-running work
    *     _ <- ctx.send(CompleteProcessing)
    *   yield ()
    * }
    * }}}
    *
    * '''Good - Fast orchestration:'''
    * {{{
    * fsm.withAtomicTransitions() { ctx =>
    *   for
    *     outcome1 <- ctx.send(ValidateOrder)
    *     state    <- ctx.currentState
    *     _        <- if state.needsApproval
    *                 then ctx.send(RequestApproval)
    *                 else ctx.send(AutoApprove)
    *   yield ()
    * }
    * }}}
    *
    * ==Lock Loss Behavior==
    *
    * By default, if the lock is lost (renewal fails), the effect is interrupted immediately (FailFast). This is safe
    * because another node may have acquired the lock. Use `Continue` only for idempotent operations.
    *
    * @param heartbeat
    *   Configuration for lock renewal (defaults to sensible values)
    * @param f
    *   The orchestration function receiving the transaction context
    * @return
    *   The result of the orchestration, or error if lock couldn't be acquired/maintained
    */
  def withAtomicTransitions[R, A](
      heartbeat: LockHeartbeatConfig = LockHeartbeatConfig()
  )(f: AtomicTransactionContext[Id, S, E] => ZIO[R, MechanoidError, A]): ZIO[R, MechanoidError | LockError, A] =
    lock.withLockAndHeartbeat(instanceId, config.nodeId, config.lockDuration, heartbeat = heartbeat) {
      val ctx = new AtomicTransactionContext[Id, S, E]:
        def send(event: E): ZIO[Any, MechanoidError, TransitionOutcome[S]] =
          underlying.send(event)
        def currentState: UIO[FSMState[S]] = underlying.state
      f(ctx)
    }
end LockedFSMRuntime

object LockedFSMRuntime:

  /** Wrap an FSMRuntime with distributed locking using default configuration.
    *
    * @param underlying
    *   The runtime to wrap
    * @param lock
    *   The distributed lock service
    * @return
    *   A new runtime that acquires locks around event processing
    */
  def apply[Id, S, E](
      underlying: FSMRuntime[Id, S, E],
      lock: FSMInstanceLock[Id],
  ): UIO[LockedFSMRuntime[Id, S, E]] =
    LockConfig.default.map(config => new LockedFSMRuntime(underlying, lock, config))

  /** Wrap an FSMRuntime with distributed locking using explicit configuration.
    *
    * @param underlying
    *   The runtime to wrap
    * @param lock
    *   The distributed lock service
    * @param config
    *   Lock configuration
    * @return
    *   A new runtime that acquires locks around event processing
    */
  def withConfig[Id, S, E](
      underlying: FSMRuntime[Id, S, E],
      lock: FSMInstanceLock[Id],
      config: LockConfig,
  ): LockedFSMRuntime[Id, S, E] =
    new LockedFSMRuntime(underlying, lock, config)
end LockedFSMRuntime

/** A transaction context for executing multiple FSM operations atomically.
  *
  * This context is provided to the function passed to [[LockedFSMRuntime.withAtomicTransitions]]. It allows sending
  * events and reading state while holding a distributed lock with automatic renewal.
  *
  * ==Intended Use==
  *
  * Fast orchestration logic that requires multiple transitions to be atomic (no other node can interleave).
  *
  * ==Anti-Pattern Warning==
  *
  * Do NOT use this to hold a lock while doing long-running work (API calls, I/O, etc.).
  *
  * @tparam Id
  *   FSM instance identifier type
  * @tparam S
  *   State type
  * @tparam E
  *   Event type
  */
trait AtomicTransactionContext[Id, S, E]:

  /** Send an event to the FSM.
    *
    * The event is processed immediately while holding the lock.
    *
    * @param event
    *   The event to send
    * @return
    *   The transition outcome
    */
  def send(event: E): ZIO[Any, MechanoidError, TransitionOutcome[S]]

  /** Get the current FSM state.
    *
    * Use this for conditional logic based on intermediate state.
    *
    * @return
    *   The current FSM state wrapper
    */
  def currentState: UIO[FSMState[S]]
end AtomicTransactionContext
