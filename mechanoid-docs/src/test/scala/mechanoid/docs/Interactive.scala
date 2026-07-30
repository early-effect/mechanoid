package mechanoid.docs

import mechanoid.docs.platform.{Demo, PublishDemo}
import specular.*
import zio.test.*

/** Interactive multi-tab / browser persistence DocSpec (shared with docsJS ClientMain). */
object Interactive extends DocSpec:

  /** Source panel for the order demo (browser `Demo` / `OrderDemoUi` app code). */
  private val orderDemoSource: String =
    """
      |import mechanoid.*
      |import mechanoid.web.*
      |import ascent.squawk.Squawk
      |
      |enum OrderState derives Finite, JsonCodec:
      |  case Pending, Paid, Shipped
      |
      |enum OrderEvent derives Finite, JsonCodec:
      |  case Pay, Ship
      |
      |val machine = Machine(
      |  assembly[OrderState, OrderEvent](
      |    Pending via Pay to Paid,
      |    Paid via Ship to Shipped,
      |  )
      |)
      |
      |for
      |  shared <- SharedFSMRuntime.stores[OrderState, OrderEvent]("mechanoid-docs")
      |  state  <- sq[OrderState](Pending)
      |  fsm    <- SharedFSMRuntime.start(
      |              "docs-order-1",
      |              machine,
      |              Pending,
      |              shared,
      |              onState = state.set,
      |            )
      |  refresh =
      |    fsm.currentState.flatMap(state.set) *>
      |      shared.sync.publish("docs-order-1")
      |  panel <- OrderDemoUi.panel(
      |    state = state,
      |    note  = ...,
      |    onPay = fsm.send(Pay).ignore *> refresh,
      |    onShip = fsm.send(Ship).ignore *> refresh,
      |    onSelectNode = id =>
      |      state.get.flatMap { cur =>
      |        eventTo(cur, id) match
      |          case Some(ev) => fsm.send(ev).ignore *> refresh
      |          case None     => ZIO.unit
      |      },
      |  )
      |yield panel
      |""".stripMargin

  /** Source panel for the publishing demo (browser `PublishDemo` / `PublishDemoUi`). */
  private val publishDemoSource: String =
    """
      |import mechanoid.*
      |import mechanoid.web.*
      |
      |sealed trait DocumentState derives Finite, JsonCodec
      |case object Draft extends DocumentState
      |sealed trait InReview extends DocumentState derives Finite, JsonCodec
      |case object PendingReview extends InReview
      |case object UnderReview extends InReview
      |case object ChangesRequested extends InReview
      |sealed trait Approval extends DocumentState derives Finite, JsonCodec
      |case object PendingApproval extends Approval
      |case object Rejected extends Approval
      |case object Published extends DocumentState
      |case object Archived extends DocumentState
      |case object Cancelled extends DocumentState
      |
      |enum DocumentEvent derives Finite, JsonCodec:
      |  case SubmitForReview, AssignReviewer, RequestChanges, ResubmitAfterChanges
      |  case ApproveReview, ApprovePublication, RejectPublication, Archive
      |  case CancelReview, Abandon, Reset
      |
      |val machine = Machine(
      |  assembly[DocumentState, DocumentEvent](
      |    all[InReview] via CancelReview to Draft,
      |    all[InReview] via Reset to Draft,
      |    all[Approval] via Abandon to Cancelled,
      |    all[Approval] via Reset to Draft,
      |    Published via Reset to Draft,
      |    Archived via Reset to Draft,
      |    Cancelled via Reset to Draft,
      |  ) ++ assembly[DocumentState, DocumentEvent](
      |    Draft via SubmitForReview to PendingReview,
      |    PendingReview via AssignReviewer to UnderReview,
      |    UnderReview via RequestChanges to ChangesRequested,
      |    UnderReview via ApproveReview to PendingApproval,
      |    ChangesRequested via ResubmitAfterChanges to PendingReview,
      |    PendingApproval via ApprovePublication to Published,
      |    PendingApproval via RejectPublication to Rejected,
      |    Rejected via SubmitForReview to PendingReview,
      |    Published via Archive to Archived,
      |  )
      |)
      |
      |def canFire(from: DocumentState, event: DocumentEvent): Boolean =
      |  machine.transitions.contains(
      |    (machine.stateEnum.caseHash(from), machine.eventEnum.caseHash(event))
      |  )
      |
      |// Role buttons: A.disabled(state.map(s => !canFire(s, event)))
      |
      |for
      |  shared <- SharedFSMRuntime.stores[DocumentState, DocumentEvent](
      |              "mechanoid-docs-publish",
      |              channelName = "mechanoid-publish-sync",
      |            )
      |  state  <- sq[DocumentState](Draft)
      |  fsm    <- SharedFSMRuntime.start(
      |              "docs-publish-1",
      |              machine,
      |              Draft,
      |              shared,
      |              onState = state.set,
      |            )
      |  refresh =
      |    fsm.currentState.flatMap(state.set) *>
      |      shared.sync.publish("docs-publish-1")
      |  panel <- PublishDemoUi.panel(
      |    state = state,
      |    note  = ...,
      |    send  = ev => fsm.send(ev).ignore *> refresh,
      |  )
      |yield panel
      |""".stripMargin

  def doc = page("Browser Persistence")(
    section("Multi-tab shared FSM")(
      md"""
Open this page in **two browser tabs**. On Scala.js the demo uses **IndexedDB** plus
**BroadcastChannel** so both tabs share one FSM instance (`docs-order-1`). A live
[mermoid](https://www.earlyeffect.rocks/mermoid/) state diagram (via `specular-mermoid`)
highlights the current state — click the next node, or use **Pay** / **Ship**.

JVM DocSpec SSR uses an in-memory store for the first paint; the live remount in the browser is
the IndexedDB path (`mechanoid-web`) with interactive diagram selection and reflow.
""",
      exampleIO {
        Demo.ui
      }.copy(source = orderDemoSource).interactive.assert(_ => assertTrue(true)),
    ),
    section("Multi-role publishing flow")(
      md"""
Same IndexedDB + BroadcastChannel stack for the hierarchical **Document Workflow** machine
(Draft → review → approval → Published). Role panels (**Writer**, **Reviewer**, **Approver**, **Ops**)
only enable actions that are legal in the current state — illegal buttons stay `disabled`.

**Reset to Draft** is a normal `DocumentEvent.Reset` transition from every non-Draft leaf (gated like the other
role actions). Peers reconstruct from IndexedDB when BroadcastChannel notifies.
""",
      exampleIO {
        PublishDemo.ui
      }.copy(source = publishDemoSource).interactive.assert(_ => assertTrue(true)),
    ),
  )
end Interactive
