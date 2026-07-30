package mechanoid.docs.platform

import ascent.*
import ascent.dsl.*
import mechanoid.*
import mechanoid.web.*
import zio.*

import scala.language.implicitConversions

/** Browser demo: IndexedDB + BroadcastChannel multi-tab shared FSM with live mermoid. */
object Demo:

  import OrderDemoUi.*
  import OrderState.*, OrderEvent.*

  private val DbName = "mechanoid-docs"

  def ui: URIO[Scope, ascent.ast.UI[Any]] =
    for
      shared <- SharedFSMRuntime
        .stores[OrderState, OrderEvent](DbName)
        .mapError(e => new RuntimeException(e.toString))
        .orDie
      state <- sq[OrderState](Pending)
      fsm   <- SharedFSMRuntime
        .start(
          InstanceId,
          machine,
          Pending,
          shared,
          onState = state.set,
        )
        .mapError(e => new RuntimeException(e.toString))
        .orDie
      refresh =
        fsm.currentState.flatMap(state.set) *> shared.sync.publish(InstanceId)
      panel <- OrderDemoUi.panel(
        state = state,
        note = E.p(
          A.className("note"),
          "Open this page in two tabs. Both share IndexedDB instance ",
          E.code(InstanceId),
          ". The diagram highlights the live state (click the next node to advance).",
        ),
        onPay = fsm.send(Pay).ignore *> refresh,
        onShip = fsm.send(Ship).ignore *> refresh,
        onSelectNode = id =>
          state.get.flatMap { cur =>
            eventTo(cur, id) match
              case Some(ev) => fsm.send(ev).ignore *> refresh
              case None     => ZIO.unit
          },
      )
    yield panel
end Demo
