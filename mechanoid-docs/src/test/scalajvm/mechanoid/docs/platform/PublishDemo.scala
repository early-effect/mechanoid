package mechanoid.docs.platform

import ascent.*
import ascent.dsl.*
import mechanoid.*
import zio.*
import zio.json.*

import scala.language.implicitConversions

/** JVM SSR publishing demo: in-memory store with gating (including Reset). */
object PublishDemo:

  import PublishDemoUi.*

  def ui: URIO[Scope, ascent.ast.UI[Any]] =
    for
      store <- InMemoryEventStore.make[String, DocumentState, DocumentEvent]()
      state <- sq[DocumentState](Draft)
      fsm   <- FSMRuntime(InstanceId, machine, Draft)
        .provideSome[Scope](
          ZLayer.succeed(store),
          TimeoutStrategy.fiber[String],
          LockingStrategy.optimistic[String],
        )
        .mapError(e => new RuntimeException(e.toString))
        .orDie
      _ <- fsm.currentState.flatMap(state.set)
      refresh = fsm.currentState.flatMap(state.set)
      panel <- PublishDemoUi.panel(
        state = state,
        note = E.p(
          A.className("note"),
          "JVM preview (in-memory). Role buttons (including Reset) are gated by the machine transition map.",
        ),
        send = ev => fsm.send(ev).ignore *> refresh,
      )
    yield panel
end PublishDemo
