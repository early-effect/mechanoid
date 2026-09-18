package mechanoid.machine

import java.time.Instant
import zio.{Clock, Duration, UIO, ZIO}

/** How a named timeout computes its absolute deadline when it is armed. */
enum TimeoutDeadline[-S]:
  /** `Clock.instant` plus this duration at arm time. */
  case After(duration: Duration)

  /** Fixed calendar instant, independent of enter time. */
  case At(instant: Instant)

  /** Instant derived from the current leaf payload at arm (and Stay re-arm) time. */
  case FromPayload(compute: S => Instant)
end TimeoutDeadline

object TimeoutDeadline:
  def instant[S](deadline: TimeoutDeadline[S], state: S): UIO[Instant] =
    deadline match
      case TimeoutDeadline.After(duration) =>
        Clock.instant.map(_.plusMillis(duration.toMillis))
      case TimeoutDeadline.At(instant) =>
        ZIO.succeed(instant)
      case TimeoutDeadline.FromPayload(compute) =>
        ZIO.succeed(compute(state))
end TimeoutDeadline

/** A timeout attached to a transition before the leaf name is resolved.
  *
  * `name` is `None` when the caller omitted it; assembly fills [[TimeoutSpec.name]] from `Finite.nameOf(event)`.
  */
final case class NamedTimeout[-S, +E](
    event: E,
    name: Option[String],
    deadline: TimeoutDeadline[S],
)

/** Builder after `Aspect.timeout(event)` / `Aspect.timeout(event, name)`. The event is already pinned. */
final class TimeoutBuilder[E](event: E, name: Option[String]):
  def apply(duration: Duration): NamedTimeout[Any, E] =
    NamedTimeout(event, name, TimeoutDeadline.After(duration))

  def apply(at: Instant): NamedTimeout[Any, E] =
    NamedTimeout(event, name, TimeoutDeadline.At(at))

  def apply[S](compute: S => Instant): NamedTimeout[S, E] =
    NamedTimeout(event, name, TimeoutDeadline.FromPayload(compute))

  def fromPayload[S](compute: S => Instant): NamedTimeout[S, E] =
    apply(compute)
end TimeoutBuilder

/** Named timeout on a leaf after assembly. `name` is always set (generated or supplied). */
final case class TimeoutSpec[S, E](
    event: E,
    name: String,
    deadline: TimeoutDeadline[S],
)
