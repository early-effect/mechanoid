package mechanoid.docs.platform

import java.time.Instant
import ascent.*
import ascent.dsl.*
import ascent.domtypes.{AttrKey, Codec}
import ascent.squawk.Squawk
import mermoid.ascent.MermoidAscent
import mermoid.{DiagramLayout, MermaidParser, Viewport}
import mechanoid.*
import specular.mermoid.Mermoid
import zio.*
import zio.json.*

import scala.language.implicitConversions

/** Live named-timeout campaign: DailyCheck Stays, EndCycle Gotos Ended. */
object NamedTimeoutDemoUi:

  enum Campaign derives Finite, JsonCodec:
    case Enqueueing, Live, Ended

  enum CampaignEvent derives Finite, JsonCodec:
    case GoLive, DailyCheck, EndCycle, Reset

  import Campaign.*
  import CampaignEvent.*

  val InstanceId = "docs-campaign-1"
  val Daily      = 3.seconds
  val Cycle      = 9.seconds

  final case class Armed(daily: Option[Instant], cycle: Option[Instant]):
    def clocks: Chunk[(String, Option[Instant])] =
      Chunk("DailyCheck" -> daily, "EndCycle" -> cycle)

  object Armed:
    val idle: Armed = Armed(None, None)

  def syncArmed(
      prev: Campaign,
      next: Campaign,
      prevSeq: Long,
      nextSeq: Long,
      now: Instant,
      current: Armed,
  ): Armed =
    val dailyAt = now.plusNanos(Daily.toNanos)
    val cycleAt = now.plusNanos(Cycle.toNanos)
    if next != Live then Armed.idle
    else if prev != Live then Armed(Some(dailyAt), Some(cycleAt))
    else if nextSeq > prevSeq then current.copy(daily = Some(dailyAt))
    else current
  end syncArmed

  val machine: Machine[Campaign, CampaignEvent] = Machine(
    assembly[Campaign, CampaignEvent](
      (Enqueueing via GoLive to Live) @@
        Aspect.timeout(DailyCheck)(Daily) @@
        Aspect.timeout(EndCycle)(Cycle),
      Live via DailyCheck to stay,
      Live via EndCycle to Ended,
      anyOf(Live, Ended) via Reset to Enqueueing,
    )
  )

  private val mermaidSource: String =
    machine.toMermaidStateDiagram(Some(Enqueueing))

  private val parsed =
    MermaidParser.parse(mermaidSource) match
      case Right(d)  => d
      case Left(err) => throw new IllegalArgumentException(s"named timeout demo diagram: $err")

  private val testId = AttrKey("data-testid", Codec.StringAsIs)

  def eventTo(from: Campaign, targetName: String): Option[CampaignEvent] =
    CampaignEvent.values.find(e => MachineGraph.destLeaf(machine, from, e).contains(targetName))

  def canFire(from: Campaign, event: CampaignEvent): Boolean =
    MachineGraph.hasEdge(machine, from, event)

  def remainingLabel(now: Instant, deadline: Instant): String =
    val ms = java.time.Duration.between(now, deadline).toMillis
    if ms <= 0 then "due"
    else if ms < 1000 then s"${ms}ms"
    else f"${ms / 1000.0}%.1fs"

  def panel(
      state: Squawk[Campaign],
      armed: Squawk[Armed],
      now: Squawk[Instant],
      note: ascent.ast.UI[Any],
      send: CampaignEvent => UIO[Unit],
      onSelectNode: String => UIO[Unit],
  ): UIO[ascent.ast.UI[Any]] =
    for width <- sq(560.0)
    yield
      val diagram = Squawk.zipWith(width, state) { (w, sel) =>
        val scene = DiagramLayout.scene(parsed, Mermoid.chalkboard, Some(Viewport(w)))
        MermoidAscent.fromScene(
          scene,
          selected = Some(sel.toString),
          onSelect = onSelectNode,
          containerWidth = Some(w),
        )
      }
      val clocks = Squawk.zipWith(armed, now) { (a, instant) =>
        a.clocks.map { case (name, deadline) =>
          (name, deadline.map(d => remainingLabel(instant, d)))
        }
      }
      E.div(
        A.className("mechanoid-multitab-demo"),
        note,
        E.div(
          A.className("mermoid-ascent mechanoid-live-fsm"),
          E.div(
            A.className("mermoid-controls"),
            E.button(A.`type`("button"), Events.onClick(_ => width.set(360.0)), "Narrow"),
            E.button(A.`type`("button"), Events.onClick(_ => width.set(560.0)), "Medium"),
            E.button(A.`type`("button"), Events.onClick(_ => width.set(720.0)), "Wide"),
            E.span(A.className("mermoid-width-label"), width.map(w => s"viewport ${w.toInt}px")),
          ),
          diagram,
        ),
        E.p(
          A.className("mechanoid-live-status"),
          "Now: ",
          E.strong(testId("state"), state.map(_.toString)),
          " · DailyCheck Stays and re-arms only itself. EndCycle Gotos Ended and cancels both.",
        ),
        E.div(
          A.className("mechanoid-timeout-rows"),
          forEach(clocks.map(_.toSeq))(t => s"${t._1}:${t._2.getOrElse("idle")}") { case (name, remaining) =>
            val idle = remaining.isEmpty
            E.div(
              A.className(if idle then "mechanoid-timeout-card is-idle" else "mechanoid-timeout-card"),
              E.div(A.className("name"), name),
              E.div(
                A.className("eta"),
                testId(s"eta-$name"),
                remaining.getOrElse("not armed"),
              ),
            )
          },
        ),
        E.div(
          A.className("mechanoid-live-actions"),
          E.button(
            testId("go-live"),
            A.disabled(state.map(s => !canFire(s, GoLive))),
            Events.onClick(_ => send(GoLive)),
            "Go live",
          ),
          E.button(
            testId("daily"),
            A.disabled(state.map(s => !canFire(s, DailyCheck))),
            Events.onClick(_ => send(DailyCheck)),
            "Fire DailyCheck",
          ),
          E.button(
            testId("end-cycle"),
            A.disabled(state.map(s => !canFire(s, EndCycle))),
            Events.onClick(_ => send(EndCycle)),
            "Fire EndCycle",
          ),
          E.button(
            testId("reset"),
            A.disabled(state.map(s => !canFire(s, Reset))),
            Events.onClick(_ => send(Reset)),
            "Reset",
          ),
        ),
      )
  end panel
end NamedTimeoutDemoUi
