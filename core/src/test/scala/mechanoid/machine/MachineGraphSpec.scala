package mechanoid.machine

import mechanoid.*
import zio.*
import zio.test.*

object MachineGraphSpec extends ZIOSpecDefault:

  enum Gate derives Finite:
    case Idle, Open, Closed

  enum Pulse derives Finite:
    case OpenGate, Close, Tick, Halt, Extra

  import Gate.*
  import Pulse.*

  val machine: Machine[Gate, Pulse] = Machine(
    assembly[Gate, Pulse](
      Idle via OpenGate to Open,
      Open via Close to Closed,
      Open via Tick to stay,
      Closed via Halt to stop("done"),
    )
  )

  sealed trait Doc derives Finite
  case object Draft         extends Doc
  sealed trait InReview     extends Doc derives Finite
  case object PendingReview extends InReview
  case object UnderReview   extends InReview
  case object Published     extends Doc

  enum DocEvent derives Finite:
    case Submit, Approve, Cancel

  import DocEvent.*

  val hierarchy: Machine[Doc, DocEvent] = Machine(
    assembly[Doc, DocEvent](
      Draft via Submit to PendingReview,
      all[InReview] via Cancel to Draft,
      UnderReview via Approve to Published,
    )
  )

  sealed trait Cell derives Finite
  case class DraftCell(n: Int, ready: Boolean) extends Cell
  case class LiveCell(n: Int)                  extends Cell

  enum CellEvent derives Finite:
    case Launch, Nudge, Finish

  import CellEvent.*

  val payload: Machine[Cell, CellEvent] = Machine(
    assembly[Cell, CellEvent](
      (state[DraftCell] via Launch).to[LiveCell] { (d, _) =>
        if d.ready then ZIO.succeed(LiveCell(d.n)) else ZIO.fail("incomplete").as(LiveCell(d.n))
      },
      (state[DraftCell] via Nudge).to(stay) { (d, _) => DraftCell(d.n + 1, d.ready) },
    )
  )

  def spec = suite("MachineGraphSpec")(
    suite("hasEdge")(
      test("true for declared Goto, Stay, and Stop") {
        assertTrue(
          MachineGraph.hasEdge(machine, Idle, OpenGate),
          MachineGraph.hasEdge(machine, Open, Close),
          MachineGraph.hasEdge(machine, Open, Tick),
          MachineGraph.hasEdge(machine, Closed, Halt),
        )
      },
      test("false when the assembly has no such pair") {
        assertTrue(
          !MachineGraph.hasEdge(machine, Idle, Close),
          !MachineGraph.hasEdge(machine, Idle, Extra),
          !MachineGraph.hasEdge(machine, Closed, OpenGate),
          !MachineGraph.hasEdge(Machine.empty[Gate, Pulse], Idle, OpenGate),
        )
      },
      test("true for every leaf under all[T]") {
        assertTrue(
          MachineGraph.hasEdge(hierarchy, PendingReview, Cancel),
          MachineGraph.hasEdge(hierarchy, UnderReview, Cancel),
          !MachineGraph.hasEdge(hierarchy, Draft, Cancel),
          !MachineGraph.hasEdge(hierarchy, Published, Cancel),
        )
      },
    ),
    suite("destLeaf")(
      test("Goto is the target leaf name") {
        assertTrue(
          MachineGraph.destLeaf(machine, Idle, OpenGate).contains("Open"),
          MachineGraph.destLeaf(machine, Open, Close).contains("Closed"),
          MachineGraph.destLeaf(hierarchy, Draft, Submit).contains("PendingReview"),
          MachineGraph.destLeaf(hierarchy, UnderReview, Approve).contains("Published"),
        )
      },
      test("Stay and Stop are the current leaf") {
        assertTrue(
          MachineGraph.destLeaf(machine, Open, Tick).contains("Open"),
          MachineGraph.destLeaf(machine, Closed, Halt).contains("Closed"),
        )
      },
      test("None when there is no edge") {
        assertTrue(
          MachineGraph.destLeaf(machine, Idle, Close).isEmpty,
          MachineGraph.destLeaf(machine, Closed, OpenGate).isEmpty,
          MachineGraph.destLeaf(hierarchy, Published, Submit).isEmpty,
        )
      },
      test("uses Finite leaf names, not payload toString") {
        val draft = DraftCell(3, ready = false)
        assertTrue(
          MachineGraph.destLeaf(payload, draft, Launch).contains("LiveCell"),
          MachineGraph.destLeaf(payload, draft, Nudge).contains("DraftCell"),
        )
      },
      test("last override wins") {
        val overridden = Machine(
          assembly[Gate, Pulse](
            Idle via OpenGate to Open,
            (Idle via OpenGate to Closed) @@ Aspect.overriding,
          )
        )
        assertTrue(
          MachineGraph.hasEdge(overridden, Idle, OpenGate),
          MachineGraph.destLeaf(overridden, Idle, OpenGate).contains("Closed"),
        )
      },
    ),
    suite("assembly edge is not reducer success")(
      test("incomplete Draft still has a Launch edge") {
        val draft = DraftCell(0, ready = false)
        assertTrue(
          MachineGraph.hasEdge(payload, draft, Launch),
          MachineGraph.destLeaf(payload, draft, Launch).contains("LiveCell"),
        )
      },
      test("send on that edge is ActionFailedError") {
        val draft = DraftCell(0, ready = false)
        ZIO.scoped {
          for
            fsm <- payload.start(draft)
            err <- fsm.send(Launch).either
            s   <- fsm.currentState
          yield err match
            case Left(_: ActionFailedError[?]) => assertTrue(s == draft)
            case other                         => assertTrue(other == null)
        }
      },
      test("no edge is InvalidTransitionError") {
        val draft = DraftCell(0, ready = true)
        ZIO.scoped {
          for
            fsm <- payload.start(draft)
            err <- fsm.send(Finish).either
            s   <- fsm.currentState
          yield err match
            case Left(_: InvalidTransitionError[?, ?]) =>
              assertTrue(s == draft, !MachineGraph.hasEdge(payload, draft, Finish))
            case other => assertTrue(other == null)
        }
      },
      test("ready Draft Launch dest is LiveCell and send succeeds") {
        val draft = DraftCell(2, ready = true)
        ZIO.scoped {
          for
            fsm <- payload.start(draft)
            _   <- fsm.send(Launch)
            s   <- fsm.currentState
          yield assertTrue(
            MachineGraph.hasEdge(payload, draft, Launch),
            MachineGraph.destLeaf(payload, draft, Launch).contains("LiveCell"),
            s == LiveCell(2),
          )
        }
      },
    ),
  )
end MachineGraphSpec
