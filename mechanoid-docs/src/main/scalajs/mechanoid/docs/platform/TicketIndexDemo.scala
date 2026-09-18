package mechanoid.docs.platform

import ascent.*
import ascent.dsl.*
import mechanoid.*
import mechanoid.web.*
import zio.*

import scala.language.implicitConversions

/** Browser demo: IndexedDB index + unique ticket numbers. */
object TicketIndexDemo:

  import TicketIndexDemoUi.*

  private val DbName = "mechanoid-docs-index"

  def ui: URIO[Scope, ascent.ast.UI[Any]] =
    for
      shared <- SharedFSMRuntime
        .stores[Ticket, TicketEvent](DbName)
        .mapError(e => new RuntimeException(e.toString))
        .orDie
      dummy = Open("x", "x", "x", 0)
      env   =
        ZLayer.succeed[EventStore[String, Ticket, TicketEvent]](shared.events) ++
          TimeoutStrategy.fiber[String] ++
          LockingStrategy.optimistic[String] ++
          ZLayer.succeed[InstanceIndex[String]](shared.index)
      birth = (t: Ticket) =>
        Random.nextUUID.flatMap { uuid =>
          val id = uuid.toString
          ZIO
            .scoped(
              FSMRuntime[String, Ticket, TicketEvent](id, machine, t, aliases, indexes).flatMap { fsm =>
                fsm.saveSnapshot.as(id)
              }
            )
            .provide(env) <* shared.sync.publish(id)
        }
      sendFn = (id: String, ev: TicketEvent) =>
        ZIO
          .scoped(
            FSMRuntime[String, Ticket, TicketEvent](id, machine, dummy, aliases, indexes).flatMap(_.send(ev).unit)
          )
          .provide(env) <* shared.sync.publish(id)
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
          .provide(env)
          .fold(_ => None, Some(_))
      panel <- TicketIndexDemoUi.panel(
        index = shared.index,
        birth = birth,
        send = sendFn,
        lookupState = lookupFn,
        note = E.p(
          A.className("note"),
          "IndexedDB. Create a ticket, then filter, page, or look it up by number.",
        ),
      )
    yield panel
end TicketIndexDemo
