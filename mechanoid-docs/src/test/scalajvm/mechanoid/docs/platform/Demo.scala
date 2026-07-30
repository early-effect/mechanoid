package mechanoid.docs.platform

import ascent.*
import ascent.dsl.*
import mechanoid.*
import zio.*
import zio.json.*

import scala.language.implicitConversions

/** JVM SSR / DocSpec demo: in-memory store + live mermoid (IndexedDB remounts in the browser). */
object Demo:

  import OrderDemoUi.*
  import OrderState.*, OrderEvent.*

  def ui: URIO[Scope, ascent.ast.UI[Any]] =
    for
      store <- InMemoryEventStore.make[String, OrderState, OrderEvent]()
      state <- sq[OrderState](Pending)
      fsm   <- FSMRuntime(InstanceId, machine, Pending)
        .provideSome[Scope](
          ZLayer.succeed(store),
          TimeoutStrategy.fiber[String],
          LockingStrategy.optimistic[String],
        )
        .mapError(e => new RuntimeException(e.toString))
        .orDie
      _ <- fsm.currentState.flatMap(state.set)
      refresh = fsm.currentState.flatMap(state.set)
      panel <- OrderDemoUi.panel(
        state = state,
        note = E.p(
          A.className("note"),
          "JVM preview (in-memory). Open the published site in two tabs for IndexedDB sync + live remount.",
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
