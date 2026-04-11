package mechanoid.stores

import zio.*
import mechanoid.core.*
import mechanoid.persistence.lock.*
import java.time.Instant

/** ZIO Ref-based in-memory FSMInstanceLock implementation.
  *
  * Thread-safe via ZIO Refs. Suitable for testing and simple single-process deployments. For distributed deployments,
  * use a database-backed implementation like PostgresInstanceLock.
  *
  * ==Usage==
  * {{{
  * // Create directly
  * for
  *   lock   <- InMemoryFSMInstanceLock.make[String]
  *   now    <- Clock.instant
  *   result <- lock.tryAcquire("instance-1", "node-1", 30.seconds, now)
  * yield result
  *
  * // Or as a ZLayer
  * val lockLayer = InMemoryFSMInstanceLock.layer[String]
  * myProgram.provide(lockLayer)
  * }}}
  */
final class InMemoryFSMInstanceLock[Id] private (
    locksRef: Ref[Map[Id, LockToken[Id]]]
) extends FSMInstanceLock[Id]:

  override def tryAcquire(
      instanceId: Id,
      nodeId: String,
      duration: Duration,
      now: Instant,
  ): ZIO[Any, MechanoidError, LockResult[Id]] =
    locksRef.modify { locks =>
      locks.get(instanceId) match
        case Some(existing) if existing.isValid(now) && existing.nodeId != nodeId =>
          // Locked by another node
          (LockResult.Busy(existing.nodeId, existing.expiresAt), locks)

        case _ =>
          // Available (no lock, expired lock, or same node re-acquiring)
          val token = LockToken(
            instanceId = instanceId,
            nodeId = nodeId,
            acquiredAt = now,
            expiresAt = now.plusMillis(duration.toMillis),
          )
          (LockResult.Acquired(token), locks + (instanceId -> token))
    }

  override def acquire(
      instanceId: Id,
      nodeId: String,
      duration: Duration,
      timeout: Duration,
  ): ZIO[Any, MechanoidError, LockResult[Id]] =
    for
      startTime <- Clock.instant
      deadline = startTime.plusMillis(timeout.toMillis)
      result <- attemptAcquire(instanceId, nodeId, duration, deadline)
    yield result

  private def attemptAcquire(
      instanceId: Id,
      nodeId: String,
      duration: Duration,
      deadline: Instant,
  ): ZIO[Any, MechanoidError, LockResult[Id]] =
    for
      now         <- Clock.instant
      result      <- tryAcquire(instanceId, nodeId, duration, now)
      finalResult <- result match
        case acquired @ LockResult.Acquired(_) =>
          ZIO.succeed(acquired)
        case LockResult.Busy(_, _) if now.isAfter(deadline) =>
          ZIO.succeed(LockResult.TimedOut[Id]())
        case LockResult.Busy(_, until) =>
          // Wait a bit and retry with exponential backoff
          val waitTime = Duration.fromMillis(
            math.min(100L, java.time.Duration.between(now, until).toMillis / 2).max(10L)
          )
          ZIO.sleep(waitTime) *> attemptAcquire(instanceId, nodeId, duration, deadline)
        case LockResult.TimedOut() =>
          // tryAcquire never returns TimedOut, but handle for exhaustivity
          ZIO.succeed(result)
    yield finalResult

  override def release(token: LockToken[Id]): ZIO[Any, MechanoidError, Boolean] =
    locksRef.modify { locks =>
      locks.get(token.instanceId) match
        case Some(existing) if existing.nodeId == token.nodeId =>
          (true, locks - token.instanceId)
        case _ =>
          // Lock doesn't exist or held by different node
          (false, locks)
    }

  override def extend(
      token: LockToken[Id],
      additionalDuration: Duration,
      now: Instant,
  ): ZIO[Any, MechanoidError, Option[LockToken[Id]]] =
    locksRef.modify { locks =>
      locks.get(token.instanceId) match
        case Some(existing) if existing.nodeId == token.nodeId =>
          val newToken = token.copy(expiresAt = now.plusMillis(additionalDuration.toMillis))
          (Some(newToken), locks + (token.instanceId -> newToken))
        case _ =>
          // Lock was lost
          (None, locks)
    }

  override def get(instanceId: Id, now: Instant): ZIO[Any, MechanoidError, Option[LockToken[Id]]] =
    locksRef.get.map(_.get(instanceId).filter(_.isValid(now)))

  /** Get the number of active locks (for testing). */
  def activeLockCount: UIO[Int] =
    for
      now   <- Clock.instant
      locks <- locksRef.get
    yield locks.values.count(_.isValid(now))

  /** Clear all locks (for testing). */
  def clear: UIO[Unit] =
    locksRef.set(Map.empty)
end InMemoryFSMInstanceLock

object InMemoryFSMInstanceLock:

  /** Create a new in-memory FSM instance lock. */
  def make[Id]: UIO[InMemoryFSMInstanceLock[Id]] =
    Ref.make(Map.empty[Id, LockToken[Id]]).map(new InMemoryFSMInstanceLock(_))
