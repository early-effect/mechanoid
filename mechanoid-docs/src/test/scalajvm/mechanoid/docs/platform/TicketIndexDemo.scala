package mechanoid.docs.platform

import ascent.*
import ascent.dsl.*
import mechanoid.*
import mechanoid.stores.{InMemoryEventStore, InMemoryInstanceIndex}
import zio.*

import scala.language.implicitConversions

/** JVM SSR / DocSpec: in-memory stores. Browser remounts the IndexedDB path. */
object TicketIndexDemo:

  import TicketIndexDemoUi.*

  def ui: URIO[Scope, ascent.ast.UI[Any]] =
    for
      store <- InMemoryEventStore.make[String, Ticket, TicketEvent]()
      index <- InMemoryInstanceIndex.make[String]
      layers = ZLayer.succeed(store) ++
        ZLayer.succeed[InstanceIndex[String]](index) ++
        TimeoutStrategy.fiber[String] ++
        LockingStrategy.optimistic[String]
      dummy = Open("x", "x", "x", 0)
      birth = (t: Ticket) =>
        Random.nextUUID.flatMap { uuid =>
          val id = uuid.toString
          ZIO
            .scoped(
              FSMRuntime[String, Ticket, TicketEvent](id, machine, t, aliases, indexes).flatMap { fsm =>
                fsm.saveSnapshot.as(id)
              }
            )
            .provide(layers)
        }
      sendFn = (id: String, ev: TicketEvent) =>
        ZIO
          .scoped(
            FSMRuntime[String, Ticket, TicketEvent](id, machine, dummy, aliases, indexes).flatMap(_.send(ev).unit)
          )
          .provide(layers)
      lookupFn = (number: String) =>
        ZIO
          .scoped {
            FSMRuntime
              .lookup[String, Ticket, TicketEvent](
                Alias.of[IndexDemoTicket].number(number),
                machine,
                dummy,
                aliases,
                indexes,
              )
              .flatMap(_.currentState)
          }
          .provide(layers)
          .fold(_ => None, Some(_))
      panel <- TicketIndexDemoUi.panel(
        index = index,
        birth = birth,
        send = sendFn,
        lookupState = lookupFn,
        note = E.p(
          A.className("note"),
          "JVM preview (in-memory). Create a ticket, then filter, page, or look it up by number.",
        ),
      )
    yield panel
end TicketIndexDemo
