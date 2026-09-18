package mechanoid.runtime

import java.time.Instant
import zio.*
import zio.test.*
import mechanoid.*
import mechanoid.persistence.*
import mechanoid.runtime.locking.OptimisticLockingStrategy
import mechanoid.runtime.timeout.FiberTimeoutStrategy
import mechanoid.stores.{InMemoryEventStore, InMemoryInstanceIndex}

final case class RuntimePersonId(raw: String)
object RuntimePersonId:
  given AliasCodec[RuntimePersonId] = _.raw

enum RuntimeTicketState derives Finite:
  case Open(
      @index assignee: RuntimePersonId,
      title: String,
      @indexCreated createdAt: Instant,
      @indexUpdated editedAt: Instant,
  )
  case Archived(
      @index assignee: RuntimePersonId,
      title: String,
      @indexCreated createdAt: Instant,
      @indexUpdated editedAt: Instant,
  )
end RuntimeTicketState

enum RuntimeTicketEvent derives Finite:
  case Edit(title: String, editedAt: Instant)
  case Archive
  case Timeout

object IndexRuntimeLaws extends ZIOSpecDefault:

  import RuntimeTicketState.*
  import RuntimeTicketEvent.*

  val t0 = Instant.parse("2020-01-01T00:00:00Z")

  val machine = Machine(
    assembly[RuntimeTicketState, RuntimeTicketEvent](
      (state[Open] via event[Edit]).to(stay) { (s, e) => s.copy(title = e.title, editedAt = e.editedAt) },
      (state[Open] via Archive).to[Archived] { (s, _) => Archived(s.assignee, s.title, s.createdAt, s.editedAt) },
      (state[Open] via Timeout).to(stay) { (s, _) => s },
    )
  )

  val extractor = IndexExtractor.derived[RuntimeTicketState]
  val key       = IndexKey("assignee", "p-1")

  def layers(
      store: InMemoryEventStore[String, RuntimeTicketState, RuntimeTicketEvent],
      index: InMemoryInstanceIndex[String],
  ) =
    ZLayer.succeed(store) ++
      ZLayer.succeed[InstanceIndex[String]](index) ++
      FiberTimeoutStrategy.layer[String] ++
      OptimisticLockingStrategy.layer[String]

  val genEdit: Gen[Any, RuntimeTicketEvent] =
    for
      title <- Gen.alphaNumericStringBounded(0, 6)
      ms    <- Gen.long(1L, 1_000_000L)
    yield Edit(title, Instant.ofEpochMilli(ms))

  val genScript: Gen[Any, List[RuntimeTicketEvent]] =
    Gen.listOfBounded(0, 6)(Gen.oneOf(genEdit, Gen.const(Timeout), Gen.const(Archive)))

  def spec = suite("IndexRuntimeLaws")(
    test("birth without send indexes the initial leaf") {
      ZIO.scoped {
        for
          store <- InMemoryEventStore.make[String, RuntimeTicketState, RuntimeTicketEvent]()
          index <- InMemoryInstanceIndex.make[String]
          _     <- ZIO
            .scoped {
              FSMRuntime(
                "t-1",
                machine,
                Open(RuntimePersonId("p-1"), "new", t0, t0),
                extractor,
              ).unit
            }
            .provide(layers(store, index))
          page <- index.find(IndexQuery(key, limit = 8))
        yield assertTrue(page.items.map(_.instanceId) == Chunk("t-1"), page.items.head.stateName == "Open")
      }
    },
    test("membership equals extractor after a random script") {
      check(genScript) { events =>
        ZIO.scoped {
          for
            store <- InMemoryEventStore.make[String, RuntimeTicketState, RuntimeTicketEvent]()
            index <- InMemoryInstanceIndex.make[String]
            start = Open(RuntimePersonId("p-1"), "new", t0, t0)
            last <- ZIO
              .scoped {
                FSMRuntime("t-1", machine, start, extractor).flatMap { fsm =>
                  ZIO
                    .foldLeft(events)(start) { (st, ev) =>
                      fsm
                        .send(ev)
                        .fold(
                          _ => st,
                          o =>
                            o.result match
                              case TransitionResult.Goto(s) => s
                              case TransitionResult.Stay(s) => s
                              case TransitionResult.Stop(_) => st,
                        )
                    }
                    .flatMap(s => fsm.currentState.as(s))
                }
              }
              .provide(layers(store, index))
            listed <- index.indexesOf("t-1").map(_.toSet)
            page   <- index.find(IndexQuery(key, limit = 8))
            wanted = extractor.indexes(last).toSet
          yield assertTrue(
            listed == wanted,
            page.items.forall(_.stateName == summon[Finite[RuntimeTicketState]].nameOf(last)) || wanted.isEmpty,
          )
        }
      }
    },
    test("timeout does not move EditedDesc; it does move TouchedDesc") {
      ZIO.scoped {
        for
          store <- InMemoryEventStore.make[String, RuntimeTicketState, RuntimeTicketEvent]()
          index <- InMemoryInstanceIndex.make[String]
          start = Open(RuntimePersonId("p-1"), "new", t0, t0)
          _ <- ZIO
            .scoped {
              FSMRuntime("t-1", machine, start, extractor).flatMap { fsm =>
                fsm.send(Timeout)
              }
            }
            .provide(layers(store, index))
          edited  <- index.find(IndexQuery(key, sort = IndexSort.EditedDesc, limit = 8))
          touched <- index.find(IndexQuery(key, sort = IndexSort.TouchedDesc, limit = 8))
          row = edited.items.head
        yield assertTrue(
          row.editedAt == t0,
          row.touchedAt != row.editedAt,
          touched.items.head.instanceId == "t-1",
        )
      }
    },
    test("archive stays findable with Except dropping it") {
      ZIO.scoped {
        for
          store <- InMemoryEventStore.make[String, RuntimeTicketState, RuntimeTicketEvent]()
          index <- InMemoryInstanceIndex.make[String]
          _     <- ZIO
            .scoped {
              FSMRuntime("t-1", machine, Open(RuntimePersonId("p-1"), "new", t0, t0), extractor)
                .flatMap(_.send(Archive))
            }
            .provide(layers(store, index))
          all    <- index.find(IndexQuery(key, filter = IndexFilter.All, limit = 8))
          active <- index.find(IndexQuery(key, filter = IndexFilter.Except(Set("Archived")), limit = 8))
        yield assertTrue(all.items.head.stateName == "Archived", active.items.isEmpty)
      }
    },
    test("reconstruct after snapshot with no extra send still finds") {
      ZIO.scoped {
        for
          store <- InMemoryEventStore.make[String, RuntimeTicketState, RuntimeTicketEvent]()
          index <- InMemoryInstanceIndex.make[String]
          ly = layers(store, index)
          _ <- ZIO
            .scoped {
              FSMRuntime("t-1", machine, Open(RuntimePersonId("p-1"), "new", t0, t0), extractor).flatMap(_.saveSnapshot)
            }
            .provide(ly)
          _ <- index.unbindInstance("t-1")
          _ <- ZIO
            .scoped {
              FSMRuntime("t-1", machine, Open(RuntimePersonId("p-1"), "new", t0, t0), extractor).unit
            }
            .provide(ly)
          page <- index.find(IndexQuery(key, limit = 8))
        yield assertTrue(page.items.map(_.instanceId) == Chunk("t-1"))
      }
    },
  ) @@ TestAspect.timeout(60.seconds)
end IndexRuntimeLaws
