package mechanoid.persistence.lock

import zio.*
import mechanoid.core.MechanoidError
import java.time.Instant
import scala.collection.mutable

/** In-memory implementation of FSMInstanceLock for testing.
  *
  * Thread-safe via synchronized blocks. Not suitable for production distributed deployments - use a database-backed
  * implementation instead.
  */
class InMemoryFSMInstanceLock[Id] extends FSMInstanceLock[Id]:
  private val locks = mutable.Map.empty[Id, LockToken[Id]]

  override def tryAcquire(
      instanceId: Id,
      nodeId: String,
      duration: Duration,
      now: Instant,
  ): ZIO[Any, MechanoidError, LockResult[Id]] =
    ZIO.succeed {
      synchronized {
        locks.get(instanceId) match
          case Some(existing) if existing.isValid(now) && existing.nodeId != nodeId =>
            // Locked by another node
            LockResult.Busy(existing.nodeId, existing.expiresAt)

          case _ =>
            // Available (no lock, expired lock, or same node re-acquiring)
            val token = LockToken(
              instanceId = instanceId,
              nodeId = nodeId,
              acquiredAt = now,
              expiresAt = now.plusMillis(duration.toMillis),
            )
            locks.put(instanceId, token)
            LockResult.Acquired(token)
      }
    }

  override def acquire(
      instanceId: Id,
      nodeId: String,
      duration: Duration,
      timeout: Duration,
  ): ZIO[Any, MechanoidError, LockResult[Id]] =
    val deadline = java.time.Instant.now().plusMillis(timeout.toMillis)

    def attempt: ZIO[Any, MechanoidError, LockResult[Id]] =
      for
        now         <- Clock.instant
        result      <- tryAcquire(instanceId, nodeId, duration, now)
        finalResult <- result match
          case acquired @ LockResult.Acquired(_) =>
            ZIO.succeed(acquired)
          case LockResult.Busy(_, until) if now.isAfter(deadline) =>
            ZIO.succeed(LockResult.TimedOut[Id]())
          case LockResult.Busy(_, until) =>
            // Wait a bit and retry with exponential backoff
            val waitTime = Duration.fromMillis(
              math.min(100, java.time.Duration.between(now, until).toMillis / 2).max(10)
            )
            ZIO.sleep(waitTime) *> attempt
          case timedOut @ LockResult.TimedOut() =>
            ZIO.succeed(timedOut)
      yield finalResult

    attempt
  end acquire

  override def release(token: LockToken[Id]): ZIO[Any, MechanoidError, Boolean] =
    ZIO.succeed {
      synchronized {
        locks.get(token.instanceId) match
          case Some(existing) if existing.nodeId == token.nodeId =>
            locks.remove(token.instanceId)
            true
          case _ =>
            // Lock doesn't exist or held by different node
            false
      }
    }

  override def extend(
      token: LockToken[Id],
      additionalDuration: Duration,
      now: Instant,
  ): ZIO[Any, MechanoidError, Option[LockToken[Id]]] =
    ZIO.succeed {
      synchronized {
        locks.get(token.instanceId) match
          case Some(existing) if existing.nodeId == token.nodeId =>
            val newToken = token.copy(
              expiresAt = now.plusMillis(additionalDuration.toMillis)
            )
            locks.put(token.instanceId, newToken)
            Some(newToken)
          case _ =>
            // Lock was lost
            None
      }
    }

  override def get(instanceId: Id, now: Instant): ZIO[Any, MechanoidError, Option[LockToken[Id]]] =
    ZIO.succeed {
      synchronized {
        locks.get(instanceId).filter(_.isValid(now))
      }
    }

  override def forceRelease(instanceId: Id): ZIO[Any, MechanoidError, Unit] =
    ZIO.succeed {
      synchronized {
        locks.remove(instanceId)
        ()
      }
    }

  /** Get the number of active locks (for testing). */
  def activeLockCount: Int = synchronized {
    val now = Instant.now()
    locks.values.count(_.isValid(now))
  }

  /** Clear all locks (for testing). */
  def clear(): Unit = synchronized {
    locks.clear()
  }
end InMemoryFSMInstanceLock
