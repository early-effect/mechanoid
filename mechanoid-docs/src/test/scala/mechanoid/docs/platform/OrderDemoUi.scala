package mechanoid.docs.platform

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

/** Shared live order FSM chrome: Specular/mermoid hybrid diagram + controls.
  *
  * The diagram remounts via docsJS (`.interactive`); selection tracks the live FSM state and clicking a reachable next
  * state fires the transition. UI state is typed [[OrderState]]; strings only at the mermoid node-id boundary.
  *
  * `Mermoid.diagramControlled` wants `Source[Option[String]]`; live state is a derived `Squawk[OrderState]`, so this
  * still paints via `fromScene`.
  */
object OrderDemoUi:

  enum OrderState derives Finite, JsonCodec:
    case Pending, Paid, Shipped

  enum OrderEvent derives Finite, JsonCodec:
    case Pay, Ship

  import OrderState.*, OrderEvent.*

  val InstanceId = "docs-order-1"

  val machine: Machine[OrderState, OrderEvent] = Machine(
    assembly[OrderState, OrderEvent](
      Pending via Pay to Paid,
      Paid via Ship to Shipped,
    )
  )

  private val mermaidSource: String =
    machine.toMermaidStateDiagram(Some(Pending))

  private val parsed =
    MermaidParser.parse(mermaidSource) match
      case Right(d)  => d
      case Left(err) => throw new IllegalArgumentException(s"order demo diagram: $err")

  private val testId = AttrKey("data-testid", Codec.StringAsIs)

  /** Map a click on a diagram node to the event that advances from `from` (if any). */
  def eventTo(from: OrderState, targetName: String): Option[OrderEvent] =
    OrderState.values.find(_.toString == targetName).flatMap { to =>
      (from, to) match
        case (Pending, Paid) => Some(Pay)
        case (Paid, Shipped) => Some(Ship)
        case _               => None
    }

  /** Build the interactive panel. `state` is the live FSM state; handlers drive transitions. */
  def panel(
      state: Squawk[OrderState],
      note: ascent.ast.UI[Any],
      onPay: UIO[Unit],
      onShip: UIO[Unit],
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
          " · click the next state on the diagram, or use the buttons",
        ),
        E.div(
          A.className("mechanoid-live-actions"),
          E.button(testId("pay"), Events.onClick(_ => onPay), "Pay"),
          E.button(testId("ship"), Events.onClick(_ => onShip), "Ship"),
        ),
      )
end OrderDemoUi
