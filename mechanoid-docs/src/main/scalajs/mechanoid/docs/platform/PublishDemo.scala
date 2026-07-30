package mechanoid.docs.platform

import ascent.*
import ascent.dsl.*
import mechanoid.*
import mechanoid.web.*
import zio.*

import scala.language.implicitConversions

/** Browser publishing demo: IndexedDB + role-gated actions (including Reset). */
object PublishDemo:

  import PublishDemoUi.*

  private val DbName = "mechanoid-docs-publish"

  def ui: URIO[Scope, ascent.ast.UI[Any]] =
    for
      shared <- SharedFSMRuntime
        .stores[DocumentState, DocumentEvent](DbName, channelName = "mechanoid-publish-sync")
        .mapError(e => new RuntimeException(e.toString))
        .orDie
      state <- sq[DocumentState](Draft)
      fsm   <- SharedFSMRuntime
        .start(
          InstanceId,
          machine,
          Draft,
          shared,
          onState = state.set,
        )
        .mapError(e => new RuntimeException(e.toString))
        .orDie
      refresh =
        fsm.currentState.flatMap(state.set) *> shared.sync.publish(InstanceId)
      panel <- PublishDemoUi.panel(
        state = state,
        note = E.p(
          A.className("note"),
          "Multi-role publishing flow on IndexedDB instance ",
          E.code(InstanceId),
          ". Illegal role actions stay disabled. ",
          E.strong("Reset"),
          " is a normal machine event (other tabs reconstruct).",
        ),
        send = ev => fsm.send(ev).ignore *> refresh,
      )
    yield panel
end PublishDemo
