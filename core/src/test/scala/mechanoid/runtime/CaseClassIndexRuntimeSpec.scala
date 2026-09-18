package mechanoid.runtime

import zio.*
import zio.test.*
import mechanoid.*
import mechanoid.persistence.*
import mechanoid.runtime.locking.OptimisticLockingStrategy
import mechanoid.runtime.timeout.FiberTimeoutStrategy
import mechanoid.stores.{InMemoryEventStore, InMemoryInstanceIndex}

/** The common product path: unique `@alias`, `@index`, birth without send, snapshot, then send Archive.
  *
  * States are sealed-trait case classes (not an enum). Events are referenced through val aliases, as UI code does.
  */
sealed trait CcTicket derives Finite
sealed trait CcActive extends CcTicket derives Finite

final case class CcOpen(
    @alias number: String,
    @index assignee: String,
) extends CcActive

final case class CcArchived(
    @alias number: String,
    @index assignee: String,
) extends CcTicket

enum CcTicketEvent derives Finite:
  case Archive, Reopen

object CaseClassIndexRuntimeSpec extends ZIOSpecDefault:

  val Archive: CcTicketEvent.Archive.type = CcTicketEvent.Archive
  val Reopen: CcTicketEvent.Reopen.type   = CcTicketEvent.Reopen

  val machine = Machine(
    assembly[CcTicket, CcTicketEvent](
      (state[CcOpen] via Archive).to[CcArchived] { (s, _) => CcArchived(s.number, s.assignee) },
      (state[CcArchived] via Reopen).to[CcOpen] { (s, _) => CcOpen(s.number, s.assignee) },
    )
  )

  val aliases = AliasExtractor.derived[CcTicket]
  val indexes = IndexExtractor.derived[CcTicket]
  val dummy   = CcOpen("x", "x")

  def layers(
      store: InMemoryEventStore[String, CcTicket, CcTicketEvent],
      index: InMemoryInstanceIndex[String],
  ) =
    ZLayer.succeed(store) ++
      ZLayer.succeed[InstanceIndex[String]](index) ++
      FiberTimeoutStrategy.layer[String] ++
      OptimisticLockingStrategy.layer[String]

  def spec = suite("case-class ticket index runtime")(
    test("birth indexes the Open leaf without send") {
      ZIO.scoped {
        for
          store <- InMemoryEventStore.make[String, CcTicket, CcTicketEvent]()
          index <- InMemoryInstanceIndex.make[String]
          _     <- ZIO
            .scoped {
              FSMRuntime("t-1", machine, CcOpen("t-1", "me"), aliases, indexes).flatMap(_.saveSnapshot)
            }
            .provide(layers(store, index))
          page <- index.find(IndexQuery.of[CcTicket].assignee("me").limit(8))
        yield assertTrue(
          page.items.map(_.instanceId) == Chunk("t-1"),
          page.items.head.stateName == "CcOpen",
        )
      }
    },
    test("reconstruct after snapshot, send Archive, query only Archived") {
      ZIO.scoped {
        for
          store <- InMemoryEventStore.make[String, CcTicket, CcTicketEvent]()
          index <- InMemoryInstanceIndex.make[String]
          ly = layers(store, index)
          _ <- ZIO
            .scoped {
              FSMRuntime("t-1", machine, CcOpen("t-1", "me"), aliases, indexes).flatMap(_.saveSnapshot)
            }
            .provide(ly)
          _ <- ZIO
            .scoped {
              FSMRuntime("t-1", machine, dummy, aliases, indexes).flatMap(_.send(Archive))
            }
            .provide(ly)
          all      <- index.find(IndexQuery.of[CcTicket].assignee("me").limit(8))
          archived <- index.find(IndexQuery.of[CcTicket].assignee("me").only(state[CcArchived]).limit(8))
          open     <- index.find(IndexQuery.of[CcTicket].assignee("me").only(state[CcOpen]).limit(8))
          byNumber <- index.resolve(Alias.of[CcTicket].number("t-1"))
        yield assertTrue(
          all.items.head.stateName == "CcArchived",
          archived.items.map(_.instanceId) == Chunk("t-1"),
          open.items.isEmpty,
          byNumber.contains("t-1"),
        )
      }
    },
  )
end CaseClassIndexRuntimeSpec
