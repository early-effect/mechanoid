package mechanoid.runtime

import java.time.{Instant, LocalDateTime, OffsetDateTime, ZoneOffset}
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import scala.DummyImplicit
import zio.*

/** One event's wall time.
  *
  * `instant`, `currentTime`, `currentDateTime`, `localDateTime`, and `javaClock` return `at`. `sleep`, `scheduler`, and
  * `nanoTime` stay on `outer`, so a sleep inside an action still follows the caller's clock.
  */
private[runtime] final class EventClock(at: Instant, outer: Clock) extends Clock:

  def instant(implicit trace: Trace): UIO[Instant] =
    ZIO.succeed(at)

  def currentDateTime(implicit trace: Trace): UIO[OffsetDateTime] =
    ZIO.succeed(at.atOffset(ZoneOffset.UTC))

  def localDateTime(implicit trace: Trace): UIO[LocalDateTime] =
    ZIO.succeed(LocalDateTime.ofInstant(at, ZoneOffset.UTC))

  def javaClock(implicit trace: Trace): UIO[java.time.Clock] =
    ZIO.succeed(java.time.Clock.fixed(at, ZoneOffset.UTC))

  def currentTime(unit: => ChronoUnit)(implicit trace: Trace, d: DummyImplicit): UIO[Long] =
    ZIO.succeed(unit.between(Instant.EPOCH, at))

  def currentTime(unit: => TimeUnit)(implicit trace: Trace): UIO[Long] =
    val nanos = Math.addExact(Math.multiplyExact(at.getEpochSecond, 1000000000L), at.getNano.toLong)
    ZIO.succeed(unit.convert(nanos, TimeUnit.NANOSECONDS))

  def nanoTime(implicit trace: Trace): UIO[Long] =
    outer.nanoTime

  def sleep(duration: => Duration)(implicit trace: Trace): UIO[Unit] =
    outer.sleep(duration)

  def scheduler(implicit trace: Trace): UIO[Scheduler] =
    outer.scheduler
end EventClock

private[runtime] object EventClock:

  /** Milliseconds: the precision IndexedDB stores and Postgres `timestamptz` round-trips. */
  def truncate(instant: Instant): Instant =
    Instant.ofEpochMilli(instant.toEpochMilli)

  def pinned[R, E, A](at: Instant)(effect: ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.clockWith(outer => effect.withClock(new EventClock(at, outer)))
end EventClock
