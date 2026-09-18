package mechanoid.docs.platform

import ascent.*
import ascent.dsl.*
import mechanoid.*
import mechanoid.stores.InMemoryEventStore
import zio.*

import scala.language.implicitConversions

/** JVM SSR / DocSpec: in-memory store + fiber named timeouts. */
object NamedTimeoutDemo:

  import NamedTimeoutDemoUi.*
  import Campaign.*

  def ui: URIO[Scope, ascent.ast.UI[Any]] =
    for
      events <- InMemoryEventStore.make[String, Campaign, CampaignEvent]()
      state  <- sq[Campaign](Enqueueing)
      now    <- Clock.instant.flatMap(t => sq(t))
      armed  <- sq(Armed.idle)
      prev   <- Ref.make((Enqueueing: Campaign, 0L))
      fsm    <- FSMRuntime(InstanceId, machine, Enqueueing)
        .provideSome[Scope](
          ZLayer.succeed(events),
          TimeoutStrategy.fiber[String],
          LockingStrategy.optimistic[String],
        )
        .mapError(e => new RuntimeException(e.toString))
        .orDie
      refresh =
        for
          st     <- fsm.currentState
          seq    <- fsm.lastSequenceNr
          n      <- Clock.instant
          curArm <- armed.get
          _      <- state.set(st)
          _      <- now.set(n)
          _      <- prev.get.flatMap { case (was, wasSeq) =>
            val next = syncArmed(was, st, wasSeq, seq, n, curArm)
            armed.set(next) *> prev.set((st, seq))
          }
        yield ()
      _     <- refresh
      _     <- refresh.repeat(Schedule.spaced(200.millis)).forkScoped
      panel <- NamedTimeoutDemoUi.panel(
        state = state,
        armed = armed,
        now = now,
        note = E.p(
          A.className("note"),
          "JVM preview (fiber timeouts). The live site remounts this in the browser.",
        ),
        send = ev => fsm.send(ev).ignore *> refresh,
        onSelectNode = id =>
          state.get.flatMap { cur =>
            eventTo(cur, id) match
              case Some(ev) => fsm.send(ev).ignore *> refresh
              case None     => ZIO.unit
          },
      )
    yield panel
end NamedTimeoutDemo
