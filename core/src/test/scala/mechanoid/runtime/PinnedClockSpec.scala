package mechanoid.runtime

import java.time.{Instant, LocalDateTime, ZoneOffset}
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import zio.*
import zio.test.*

/** Proves [[EventClock]] under `ZIO.withClock`.
  *
  * Wall time is the pinned instant. `sleep` and the scheduler stay on the clock captured outside the pin, so
  * `TestClock.adjust` still wakes a sleep. `nanoTime` follows that outer clock too.
  */
object PinnedClockSpec extends ZIOSpecDefault:

  private val pin = Instant.parse("2026-06-01T00:00:00.123456789Z")
  private val t0  = Instant.parse("2026-01-01T00:00:00Z")
  private val t2  = Instant.parse("2026-03-01T00:00:00Z")

  def spec = suite("ZIO.withClock pins wall time and delegates sleep")(
    test("wall time is the pin, nanoTime and the scheduler stay on the outer clock"):
      for
        _          <- TestClock.setTime(t0)
        outer      <- ZIO.clock
        outerNanos <- outer.nanoTime
        pinned = new EventClock(pin, outer)
        inside <-
          (for
            now    <- Clock.instant
            millis <- Clock.currentTime(ChronoUnit.MILLIS)
            unitMs <- Clock.currentTime(TimeUnit.MILLISECONDS)
            date   <- Clock.currentDateTime
            local  <- Clock.localDateTime
            jClock <- Clock.javaClock
            nanos  <- Clock.nanoTime
          yield (now, millis, unitMs, date, local, jClock.instant(), nanos)).withClock(pinned)
        restored <- Clock.instant
        (now, millis, unitMs, date, local, jNow, nanos) = inside
      yield assertTrue(
        now == pin,
        millis == pin.toEpochMilli,
        unitMs == pin.toEpochMilli,
        date == pin.atOffset(ZoneOffset.UTC),
        local == LocalDateTime.ofInstant(pin, ZoneOffset.UTC),
        jNow == pin,
        nanos == outerNanos,
        restored == t0,
      )
    ,
    test("the pinned scheduler is the outer TestClock scheduler"):
      val hit = new java.util.concurrent.atomic.AtomicBoolean(false)
      for
        outer <- ZIO.clock
        pinned = new EventClock(pin, outer)
        _ <-
          Clock.scheduler
            .flatMap { sched =>
              ZIO.succeed(Unsafe.unsafe { implicit u =>
                val task: Runnable = () => hit.set(true)
                sched.schedule(task, 500.millis)
              })
            }
            .withClock(pinned)
        before <- ZIO.succeed(hit.get)
        _      <- TestClock.adjust(500.millis)
      yield assertTrue(!before, hit.get)
    ,
    test("sleep inside the pin wakes when the outer TestClock advances, and the pin does not move"):
      for
        _     <- TestClock.setTime(t0)
        outer <- ZIO.clock
        pinned = new EventClock(pin, outer)
        fiber <-
          (for
            _     <- ZIO.sleep(1.second)
            after <- Clock.instant
          yield after).withClock(pinned).fork
        _        <- TestClock.adjust(1.second)
        after    <- fiber.join
        restored <- Clock.instant
      yield assertTrue(after == pin, restored == t0.plusSeconds(1))
    ,
    test("advancing the outer clock inside the pin does not change the pinned instant"):
      ZIO.clock.flatMap { outer =>
        val pinned = new EventClock(pin, outer)
        (for
          _       <- TestClock.setTime(t2)
          now     <- Clock.instant
          nanos   <- Clock.nanoTime
          outerNs <- outer.nanoTime
        yield assertTrue(now == pin, nanos == outerNs, nanos != ChronoUnit.NANOS.between(Instant.EPOCH, pin)))
          .withClock(pinned)
      },
  )
end PinnedClockSpec
