package mechanoid.docs

import ascent.*
import ascent.dom
import zio.*

/** Browser entry: mount each interactive example into its SSR `#<page-slug>-ex-N` wrapper. */
object ClientMain extends ZIOAppDefault:

  private val pages = Vector(Interactive.doc)

  def run =
    val examples = ExampleRegistry.fromPages(pages*)
    for
      _ <- ZIO.foreachDiscard(examples.toList) { case (id, body) =>
        mountExample(id, body)
      }
      _ <- ZIO.never
    yield ()
  end run

  private def mountExample(id: String, body: URIO[Scope, ascent.ast.UI[Any]]): URIO[Scope, Unit] =
    val el = Dom.document.getElementById(id)
    if el == null then ZIO.unit
    else
      for
        _ <- ZIO.succeed(clearChildren(el))
        // Keep Scope open for IndexedDB stores / FSM fibers until the app exits.
        ui <- body
        _  <- AscentApp.mount(ui, el)
      yield ()
  end mountExample

  private def clearChildren(el: dom.Element): Unit =
    el.innerHTML = ""
end ClientMain
