package mechanoid.persistence.postgres

import saferis.*
import saferis.postgres.given
import zio.*
import mechanoid.core.{MechanoidError, PersistenceError}
import mechanoid.persistence.lock.*
import java.time.Instant

/** PostgreSQL implementation of FSMInstanceLock using Saferis.
  *
  * Uses atomic INSERT ... ON CONFLICT to implement lease-based locking with automatic expiration for crash recovery.
  */
class PostgresInstanceLock(transactor: Transactor) extends FSMInstanceLock[String]:

  override def tryAcquire(
      instanceId: String,
      nodeId: String,
      duration: Duration,
      now: Instant,
  ): ZIO[Any, MechanoidError, LockResult[String]] =
    val expiresAt = now.plusMillis(duration.toMillis)
    val row       = LockRow(instanceId, nodeId, now, expiresAt)
    transactor
      .run {
        Upsert[LockRow]
          .values(row)
          .onConflict(_.instanceId)
          .doUpdateAll
          .where(_.expiresAt)
          .lt(now)
          .or(_.nodeId)
          .eqExcluded
          .returning
          .queryOne
      }
      .flatMap {
        case Some(lockRow) =>
          ZIO.succeed[LockResult[String]](
            LockResult.Acquired(LockToken(instanceId, lockRow.nodeId, lockRow.acquiredAt, lockRow.expiresAt))
          )
        case None =>
          // Lock held by another node - get current holder info
          transactor
            .run {
              Query[LockRow]
                .where(_.instanceId)
                .eq(instanceId)
                .queryOne[LockRow]
            }
            .map {
              case Some(lockRow) => LockResult.Busy[String](lockRow.nodeId, lockRow.expiresAt)
              case None          => LockResult.Busy[String]("unknown", now) // Shouldn't happen
            }
      }
      .mapError(PersistenceError.fromError)
  end tryAcquire

  override def acquire(
      instanceId: String,
      nodeId: String,
      duration: Duration,
      timeout: Duration,
  ): ZIO[Any, MechanoidError, LockResult[String]] =
    val deadline = java.time.Instant.now().plusMillis(timeout.toMillis)

    def attempt: ZIO[Any, MechanoidError, LockResult[String]] =
      for
        now         <- Clock.instant
        _           <- ZIO.when(now.isAfter(deadline))(ZIO.succeed(LockResult.TimedOut[String]()))
        result      <- tryAcquire(instanceId, nodeId, duration, now)
        finalResult <- result match
          case acquired: LockResult.Acquired[String] => ZIO.succeed(acquired)
          case LockResult.Busy(_, until)             =>
            if now.isAfter(deadline) then ZIO.succeed(LockResult.TimedOut[String]())
            else
              val waitTime = java.time.Duration.between(now, until).toMillis.min(100L).max(10L)
              ZIO.sleep(Duration.fromMillis(waitTime)) *> attempt
          case timedOut: LockResult.TimedOut[String] => ZIO.succeed(timedOut)
      yield finalResult

    attempt
  end acquire

  override def release(token: LockToken[String]): ZIO[Any, MechanoidError, Boolean] =
    transactor
      .run {
        Delete[LockRow]
          .where(_.instanceId)
          .eq(token.instanceId)
          .where(_.nodeId)
          .eq(token.nodeId)
          .build
          .dml
      }
      .map(_ > 0)
      .mapError(PersistenceError.fromError)

  override def extend(
      token: LockToken[String],
      additionalDuration: Duration,
      now: Instant,
  ): ZIO[Any, MechanoidError, Option[LockToken[String]]] =
    val newExpiry = now.plusMillis(additionalDuration.toMillis)
    transactor
      .run {
        Update[LockRow]
          .set(_.expiresAt, newExpiry)
          .where(_.instanceId)
          .eq(token.instanceId)
          .where(_.nodeId)
          .eq(token.nodeId)
          .where(_.expiresAt)
          .gt(now)
          .returningAs
          .queryOne
      }
      .map(_.map(lockRow => LockToken(token.instanceId, lockRow.nodeId, lockRow.acquiredAt, lockRow.expiresAt)))
      .mapError(PersistenceError.fromError)
  end extend

  override def get(instanceId: String, now: Instant): ZIO[Any, MechanoidError, Option[LockToken[String]]] =
    transactor
      .run {
        Query[LockRow]
          .where(_.instanceId)
          .eq(instanceId)
          .where(_.expiresAt)
          .gt(now)
          .queryOne[LockRow]
      }
      .map(_.map(lockRow => LockToken(instanceId, lockRow.nodeId, lockRow.acquiredAt, lockRow.expiresAt)))
      .mapError(PersistenceError.fromError)
end PostgresInstanceLock

object PostgresInstanceLock:
  val layer: ZLayer[Transactor, Nothing, FSMInstanceLock[String]] =
    ZLayer.fromFunction(new PostgresInstanceLock(_))
