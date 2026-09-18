package mechanoid.docs

import java.time.Instant
import mechanoid.docs.DocZIO.*
import mechanoid.docs.platform.{IndexDemoArchived, IndexDemoOpen, IndexDemoTicket, TicketIndexDemo, TicketIndexDemoUi}
import mechanoid.*
import mechanoid.persistence.*
import mechanoid.runtime.locking.OptimisticLockingStrategy
import mechanoid.runtime.timeout.FiberTimeoutStrategy
import mechanoid.stores.{InMemoryEventStore, InMemoryInstanceIndex}
import specular.*
import zio.*
import zio.test.*

sealed trait DocsTicket derives Finite
sealed trait DocsActive extends DocsTicket derives Finite

final case class DocsOpen(
    @index assignee: String,
    @index project: String,
    @indexRank priority: Int,
) extends DocsActive

final case class DocsInProgress(
    @index assignee: String,
    @index project: String,
    @indexRank priority: Int,
) extends DocsActive

final case class DocsArchived(
    @index assignee: String,
    @index project: String,
    @indexRank priority: Int,
) extends DocsTicket

final case class DocsClocked(
    @index assignee: String,
    @indexCreated createdAt: Instant,
    @indexUpdated editedAt: Instant,
) extends DocsTicket

enum DocsTicketEvent derives Finite:
  case Tick

val docsTicketExtractor: IndexExtractor[DocsTicket] = IndexExtractor.derived[DocsTicket]

object Indexing extends DocSpec:

  type TicketId = String

  val t0 = Instant.parse("2020-01-01T00:00:00Z")
  val t1 = Instant.parse("2020-01-02T00:00:00Z")
  val t2 = Instant.parse("2020-01-03T00:00:00Z")

  def layers[S: Tag, E: Tag](
      store: InMemoryEventStore[TicketId, S, E],
      index: InMemoryInstanceIndex[TicketId],
  ) =
    ZLayer.succeed(store) ++
      ZLayer.succeed[InstanceIndex[TicketId]](index) ++
      FiberTimeoutStrategy.layer[TicketId] ++
      OptimisticLockingStrategy.layer[TicketId]

  def meta(name: String, t: Instant, rank: Long = 0L) =
    IndexMeta(name, t, t, Some(IndexClocks(t, t)), rank)

  def leafName[S: Finite](s: S): String = summon[Finite[S]].nameOf(s)

  val openName     = leafName[DocsTicket](DocsOpen("p-1", "alpha", 1))
  val doingName    = leafName[DocsTicket](DocsInProgress("p-1", "alpha", 1))
  val archivedName = leafName[DocsTicket](DocsArchived("p-1", "alpha", 1))
  val demoOpen     = leafName[IndexDemoTicket](IndexDemoOpen("n", "me", "alpha", 1))
  val demoArch     = leafName[IndexDemoTicket](IndexDemoArchived("n", "me", "alpha", 1))

  def doc = page("Indexing")(
    section("Why not alias")(
      md"""
Aliases are unique: `Alias.of[S].campaign(id)` maps to one instance. `UniqueAliasError`
still holds when index rows exist for the same instance. A person with N tickets is the
opposite: many instances share one key. Query that with `IndexQuery.of[S].assignee(me)`,
not `Alias.of`.
""",
      exampleZIO {
        enum S derives Finite:
          case Open(@alias owner: String, @index assignee: String)
        (for
          index <- InMemoryInstanceIndex.make[String]
          _     <- index.bind(Alias.of[S].owner("p-1"), "unique")
          clash <- index.bind(Alias.of[S].owner("p-1"), "other").either
          _     <- index.bindIndexes(
            Chunk(IndexQuery.of[S].assignee("p-1").toQuery[String].key),
            "t-1",
            meta("Open", t0),
          )
          _ <- index.bindIndexes(
            Chunk(IndexQuery.of[S].assignee("p-1").toQuery[String].key),
            "t-2",
            meta("Open", t1),
          )
          page <- index.find(IndexQuery.of[S].assignee("p-1").limit(8))
        yield (clash.isLeft, page.items.map(_.instanceId).toSet)).asDoc
      }.assert { case (clash, ids) => assertTrue(clash, ids == Set("t-1", "t-2")) },
    ),
    section("Extractor is a function first")(
      md"""
`IndexExtractor.apply` is the API. Nested payloads write `f` by hand (the function body
may encode keys internally). Query call sites still use `IndexQuery.of[S].assignee(me)`,
not a string. The macro walks constructor parameters only, not nested products.
""",
      exampleZIO {
        final case class Body(assignee: String, createdAt: Instant, editedAt: Instant)
        enum Ticket derives Finite:
          case Open(body: Body)
          case Archived(body: Body)
        val ext = IndexExtractor[Ticket](
          keys = {
            case Ticket.Open(b)     => Chunk(IndexQuery.of[DocsTicket].assignee(b.assignee).toQuery[String].key)
            case Ticket.Archived(b) => Chunk(IndexQuery.of[DocsTicket].assignee(b.assignee).toQuery[String].key)
          },
          clocksOf = {
            case Ticket.Open(b)     => Some(IndexClocks(b.createdAt, b.editedAt))
            case Ticket.Archived(b) => Some(IndexClocks(b.createdAt, b.editedAt))
          },
        )
        val s = Ticket.Open(Body("p-1", t0, t1))
        ZIO.succeed((ext.indexes(s).map(_.namespace), ext.clocks(s).isDefined))
      }.assert { case (ns, hasClocks) =>
        assertTrue(ns == Chunk("assignee"), hasClocks)
      },
    ),
    section("@alias and @index are members")(
      md"""
There is no string parameter on `@alias` / `@index`. The namespace is the constructor
param name. `assigneeId` stays `assigneeId`. Rename the field when you want a different
member at the query site (`IndexQuery.of[S].assignee(me)`).
""",
      exampleValue {
        docsTicketExtractor.indexes(DocsOpen("p-1", "alpha", 3)).map(_.namespace).toSet
      }.assert(ns => assertTrue(ns == Set("assignee", "project"))),
    ),
    section("Domain clocks")(
      md"""
`@indexCreated` / `@indexUpdated` copy `Instant`s from the state onto the covering row.
Inbox: `.sort(IndexSort.EditedDesc)`. Activity feed: `TouchedDesc`. Without domain clocks
they agree (`edited_at == touched_at`).
""",
      exampleValue {
        docsTicketExtractor.clocks(DocsClocked("p-1", t0, t1))
      }.assert(c => assertTrue(c.contains(IndexClocks(Some(t0), Some(t1))))),
      exampleZIO {
        val q = IndexQuery.of[DocsTicket].assignee("p-1")
        (for
          index <- InMemoryInstanceIndex.make[String]
          _     <- index.bindIndexes(
            Chunk(q.toQuery[String].key),
            "t-1",
            IndexMeta("Open", t0, t2, Some(IndexClocks(t0, t1))),
          )
          _ <- index.bindIndexes(
            Chunk(q.toQuery[String].key),
            "t-2",
            IndexMeta("Open", t0, t1, Some(IndexClocks(t0, t2))),
          )
          edited  <- index.find(q.sort(IndexSort.EditedDesc).limit(8))
          touched <- index.find(q.sort(IndexSort.TouchedDesc).limit(8))
        yield (edited.items.map(_.instanceId), touched.items.map(_.instanceId))).asDoc
      }.assert { case (ed, th) =>
        assertTrue(ed == Chunk("t-2", "t-1"), th == Chunk("t-1", "t-2"))
      },
    ),
    section("My Archived assignments")(
      md"""
`.assignee(me).only(state[Archived])` is equality on the btree suffix
`(namespace, index_key, state_name)`. Postgres pushes `state_name IN (...)`.
That is not `Except`, and not a global state key.
""",
      exampleZIO {
        val q = IndexQuery.of[DocsTicket].assignee("p-1")
        (for
          index <- InMemoryInstanceIndex.make[String]
          _     <- index.bindIndexes(Chunk(q.toQuery[String].key), "open", meta(openName, t0))
          _     <- index.bindIndexes(Chunk(q.toQuery[String].key), "arch", meta(archivedName, t1))
          page  <- index.find(q.only(state[DocsArchived]).limit(8))
          n     <- index.count(q.only(state[DocsArchived]))
        yield (page.items.map(_.instanceId), n)).asDoc
      }.assert { case (ids, n) => assertTrue(ids == Chunk("arch"), n == 1L) },
    ),
    section("only, all, and except")(
      md"""
`.only(state[Open], state[InProgress])` or `.only(all[Active])` names the leaves.
`.except(state[Archived])` is correct but walks the other leaves for that key. Prefer
`only` when the inbox must not touch archived rows even as a btree subrange.
""",
      exampleZIO {
        val q = IndexQuery.of[DocsTicket].assignee("p-1")
        (for
          index  <- InMemoryInstanceIndex.make[String]
          _      <- index.bindIndexes(Chunk(q.toQuery[String].key), "open", meta(openName, t0))
          _      <- index.bindIndexes(Chunk(q.toQuery[String].key), "doing", meta(doingName, t1))
          _      <- index.bindIndexes(Chunk(q.toQuery[String].key), "arch", meta(archivedName, t2))
          active <- index.find(q.only(all[DocsActive]).limit(8))
          pair   <- index.find(q.only(state[DocsOpen], state[DocsInProgress]).limit(8))
          except <- index.find(q.except(state[DocsArchived]).limit(8))
        yield (
          active.items.map(_.instanceId).toSet,
          pair.items.map(_.instanceId).toSet,
          except.items.map(_.instanceId).toSet,
        )).asDoc
      }.assert { case (active, pair, except) =>
        assertTrue(active == Set("open", "doing"), pair == active, except == active)
      },
    ),
    section("require AND")(
      md"""
`.assignee(me).require.project(proj)` intersects posting lists. The access path (`assignee`)
owns covering rows and the cursor. Extra equalities are filters. Missing require keys yield
an empty page.
""",
      exampleZIO {
        (for
          index <- InMemoryInstanceIndex.make[String]
          a = IndexQuery.of[DocsTicket].assignee("p-1")
          _ <- index.bindIndexes(
            Chunk(a.toQuery[String].key, IndexQuery.of[DocsTicket].project("alpha").toQuery[String].key),
            "both",
            meta("Open", t0),
          )
          _    <- index.bindIndexes(Chunk(a.toQuery[String].key), "only-me", meta("Open", t1))
          page <- index.find(a.require.project("alpha").limit(8))
        yield page.items.map(_.instanceId)).asDoc
      }.assert(ids => assertTrue(ids == Chunk("both"))),
    ),
    section("Rank")(
      md"""
`@indexRank` covers an `Int` / `Long` / `Short` (widened to `Long`, default `0`).
`.sort(IndexSort.RankDesc).rankMin(3L)` ranges on that column. Equality keys cannot range.
""",
      exampleZIO {
        val q = IndexQuery.of[DocsTicket].assignee("p-1")
        (for
          index <- InMemoryInstanceIndex.make[String]
          k = q.toQuery[String].key
          _    <- index.bindIndexes(Chunk(k), "lo", meta("Open", t0, 1L))
          _    <- index.bindIndexes(Chunk(k), "hi", meta("Open", t1, 9L))
          page <- index.find(q.sort(IndexSort.RankDesc).rankMin(3L).limit(8))
        yield (page.items.map(_.instanceId), docsTicketExtractor.rank(DocsOpen("p-1", "alpha", 4)))).asDoc
      }.assert { case (ids, rank) => assertTrue(ids == Chunk("hi"), rank.contains(4L)) },
    ),
    section("Cursor pagination")(
      md"""
Exclusive `startAfter` (seek after the last row), `IndexPage.cursor`, `hasMore` when
`items.size == limit`. No `OFFSET`. The cursor is `(IndexScalar, instanceId)`.
""",
      exampleZIO {
        val q = IndexQuery.of[DocsTicket].assignee("p-1").sort(IndexSort.EditedAsc).limit(1)
        (for
          index <- InMemoryInstanceIndex.make[String]
          k = q.toQuery[String].key
          _  <- index.bindIndexes(Chunk(k), "a", meta("Open", t0))
          _  <- index.bindIndexes(Chunk(k), "b", meta("Open", t1))
          p1 <- index.find(q)
          p2 <- index.find(q.startAfter(p1.cursor.get))
          p3 <- index.find(q.startAfter(p2.cursor.get))
        yield (p1.items.map(_.instanceId), p2.items.map(_.instanceId), p3.items.isEmpty, p1.hasMore)).asDoc
      }.assert { case (a, b, empty, more) =>
        assertTrue(a == Chunk("a"), b == Chunk("b"), empty, more)
      },
    ),
    section("Birth without send")(
      md"""
`IndexExtractor.indexes(initial)` is bound on reconstruct / `apply`, not only after append.
A cold store with no events still `find`s the instance.
""",
      exampleZIO {
        val machine = Machine(
          assembly[DocsTicket, DocsTicketEvent](
            (state[DocsOpen] via DocsTicketEvent.Tick).to(stay) { (s, _) => s }
          )
        )
        ZIO.scoped {
          for
            store <- InMemoryEventStore.make[TicketId, DocsTicket, DocsTicketEvent]()
            index <- InMemoryInstanceIndex.make[TicketId]
            _     <- ZIO
              .scoped {
                FSMRuntime("t-1", machine, DocsOpen("p-1", "alpha", 1), docsTicketExtractor).unit
              }
              .provide(layers(store, index))
            page <- index.find(IndexQuery.of[DocsTicket].assignee("p-1").limit(8))
          yield page.items.map(_.instanceId)
        }.asDoc
      }.assert(ids => assertTrue(ids == Chunk("t-1"))),
    ),
    section("Do not scan snapshots")(
      md"""
Hydrate from `EventStore` only for the `instanceId`s `find` returned, and only for fields
that are not on `Indexed`. The store column for a leaf is Finite's simple name; the query
API is `state[Archived]`, not that string.
""",
      exampleZIO {
        (for
          index <- InMemoryInstanceIndex.make[String]
          _     <- index.bindIndexes(
            Chunk(IndexQuery.of[DocsTicket].assignee("p-1").toQuery[String].key),
            "t-1",
            meta("Open", t0),
          )
          page <- index.find(IndexQuery.of[DocsTicket].assignee("p-1").limit(8))
        yield page.items.head).asDoc
      }.assert(row => assertTrue(row.instanceId == "t-1", row.stateName == "Open", row.editedAt == t0)),
    ),
    section("Namespace discipline")(
      md"""
`InstanceIndex` is not parameterized by state type. One table per database. Do not share
`assignee` across unrelated machines; pin `S` with `IndexQuery.of[Ticket]` vs
`IndexQuery.of[Doc]`. Same rule as `Alias.of[S]`.
""",
      exampleValue {
        (
          IndexQuery.of[DocsTicket].assignee("p-1").toQuery[String].key.namespace,
          IndexQuery.of[IndexDemoTicket].assignee("p-1").toQuery[String].key.namespace,
        )
      }.assert { case (a, b) => assertTrue(a == "assignee", b == "assignee", a == b) },
    ),
    section("Backends")(
      md"""
| Backend | Store | Access path |
|---------|-------|-------------|
| In-memory | `Map[IndexKey, Map[Id, row]]` | `forward.get(key)` then filter that set |
| PostgreSQL | `fsm_indexes` | `WHERE namespace AND index_key` plus `state_name IN` for `.only`; rank btree |
| IndexedDB | `indexes` object store, database version 4 | `byKey`; rank on the row; v3 databases upgrade |

`find` does not read `fsm_events` or `fsm_snapshots`.
"""
    ),
    section("Compile-time")(
      md"""
Unknown members and leaves that are not in `S` fail compilation (`report.errorAndAbort`).
A string literal namespace is rejected. `typeCheck` is the test, not a runtime lookup.
""",
      exampleZIO {
        val unknown = typeCheck("""
          import mechanoid.*
          import mechanoid.persistence.IndexQuery
          enum T derives Finite:
            case Open(@index assignee: String)
          IndexQuery.of[T].typo("x")
        """)
        val badLeaf = typeCheck("""
          import mechanoid.*
          import mechanoid.machine.state
          import mechanoid.persistence.IndexQuery
          enum T derives Finite:
            case Open(@index assignee: String)
          enum Other derives Finite:
            case Archived
          IndexQuery.of[T].assignee("p").only(state[Other.Archived])
        """)
        unknown.zip(badLeaf)
      }.assert { case (u, b) => assertTrue(u.isLeft, b.isLeft) },
    ),
    section("Live ticket index")(
      md"""
The panel below is a ticket inbox. Create indexes the ticket immediately. Number is
`Alias.of[S].number`. Filters are `.assignee`, `.only(state[Archived])`, `.only(all[Active])`,
`.except`, `.require.project`, rank sort, `rankMin`, and cursor paging. The list is `find`,
not tickets stashed in the panel.
""",
      exampleZIO {
        (for
          index <- InMemoryInstanceIndex.make[String]
          k = IndexQuery.of[IndexDemoTicket].assignee(TicketIndexDemoUi.Me).toQuery[String].key
          _        <- index.bindIndexes(Chunk(k), "t-open", meta(demoOpen, t0, 1L))
          _        <- index.bindIndexes(Chunk(k), "t-arch", meta(demoArch, t1, 2L))
          archived <- index.find(
            TicketIndexDemoUi.queryOf(
              TicketIndexDemoUi.Me,
              TicketIndexDemoUi.Chip.ArchivedOnly,
              None,
              TicketIndexDemoUi.SortPick.Edited,
              None,
              None,
              8,
            )
          )
          page1 <- index.find(
            TicketIndexDemoUi.queryOf(
              TicketIndexDemoUi.Me,
              TicketIndexDemoUi.Chip.All,
              None,
              TicketIndexDemoUi.SortPick.Edited,
              None,
              None,
              1,
            )
          )
          page2 <- index.find(
            TicketIndexDemoUi.queryOf(
              TicketIndexDemoUi.Me,
              TicketIndexDemoUi.Chip.All,
              None,
              TicketIndexDemoUi.SortPick.Edited,
              None,
              page1.cursor,
              1,
            )
          )
        yield (
          archived.items.map(_.instanceId),
          (page1.items ++ page2.items).map(_.instanceId).toSet,
        )).asDoc
      }.assert { case (arch, pages) =>
        assertTrue(arch == Chunk("t-arch"), pages == Set("t-open", "t-arch"))
      },
      exampleIO {
        TicketIndexDemo.ui
      }.interactive.assert(ui => assertTrue(ui.toString.nonEmpty)),
    ),
  )
end Indexing
