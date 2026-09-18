package mechanoid.persistence

import java.time.Instant
import zio.*
import zio.test.*
import mechanoid.*

final case class IndexPersonId(raw: String)
object IndexPersonId:
  given AliasCodec[IndexPersonId] = _.raw

enum IndexedTicketState derives Finite:
  case Draft
  case Open(
      @index assignee: IndexPersonId,
      title: String,
      @indexCreated createdAt: Instant,
      @indexUpdated editedAt: Instant,
  )
  case Archived(
      @index assignee: IndexPersonId,
      title: String,
      @indexCreated createdAt: Instant,
      @indexUpdated editedAt: Instant,
  )
end IndexedTicketState

final case class IndexedTicketBody(assignee: IndexPersonId, createdAt: Instant, editedAt: Instant)

enum NestedIndexedState derives Finite:
  case Open(body: IndexedTicketBody)
  case Archived(body: IndexedTicketBody)

enum IndexedMany derives Finite:
  case Live(@index ids: List[String])

enum IndexedOpt derives Finite:
  case Live(@index maybe: Option[String])

enum RankedTicket derives Finite:
  case Open(@index assignee: String, @indexRank priority: Int)
  case Archived(@index assignee: String)

object IndexExtractorSpec extends ZIOSpecDefault:

  val t0 = Instant.parse("2020-01-01T00:00:00Z")
  val t1 = Instant.parse("2020-01-02T00:00:00Z")

  def spec = suite("IndexExtractor")(
    test("empty states yield no keys or clocks") {
      val ext = IndexExtractor.derived[IndexedTicketState]
      val got = ext.indexes(IndexedTicketState.Draft)
      val clk = ext.clocks(IndexedTicketState.Draft)
      assertTrue(got.isEmpty, clk.isEmpty)
    },
    test("omitted namespace is the field name") {
      val ext   = IndexExtractor.derived[IndexedTicketState]
      val state = IndexedTicketState.Open(IndexPersonId("p-1"), "hi", t0, t1)
      val keys  = ext.indexes(state)
      val clk   = ext.clocks(state)
      assertTrue(
        keys == Chunk(IndexKey("assignee", "p-1")),
        clk.contains(IndexClocks(Some(t0), Some(t1))),
      )
    },
    test("explicit namespace is used") {
      val ext   = IndexExtractor.derived[IndexedTicketState]
      val state = IndexedTicketState.Archived(IndexPersonId("p-1"), "hi", t0, t1)
      assertTrue(ext.indexes(state) == Chunk(IndexKey("assignee", "p-1")))
    },
    test("apply reads nested payload") {
      val ext = IndexExtractor[NestedIndexedState](
        keys = {
          case NestedIndexedState.Open(b)     => Chunk(IndexKey("assignee", b.assignee.raw))
          case NestedIndexedState.Archived(b) => Chunk(IndexKey("assignee", b.assignee.raw))
        },
        clocksOf = {
          case NestedIndexedState.Open(b)     => Some(IndexClocks(b.createdAt, b.editedAt))
          case NestedIndexedState.Archived(b) => Some(IndexClocks(b.createdAt, b.editedAt))
        },
      )
      val state = NestedIndexedState.Open(IndexedTicketBody(IndexPersonId("p-9"), t0, t1))
      assertTrue(
        ext.indexes(state) == Chunk(IndexKey("assignee", "p-9")),
        ext.clocks(state).contains(IndexClocks(Some(t0), Some(t1))),
      )
    },
    test("derived does not see nested body fields") {
      val ext   = IndexExtractor.derived[NestedIndexedState]
      val state = NestedIndexedState.Open(IndexedTicketBody(IndexPersonId("p-9"), t0, t1))
      assertTrue(ext.indexes(state).isEmpty, ext.clocks(state).isEmpty)
    },
    test("list fields emit one key per element and collapse in the store, not the extractor") {
      val ext = IndexExtractor.derived[IndexedMany]
      val got = ext.indexes(IndexedMany.Live(List("a", "a", "b")))
      assertTrue(got == Chunk(IndexKey("ids", "a"), IndexKey("ids", "a"), IndexKey("ids", "b")))
    },
    test("None option contributes nothing") {
      val ext = IndexExtractor.derived[IndexedOpt]
      assertTrue(
        ext.indexes(IndexedOpt.Live(None)).isEmpty,
        ext.indexes(IndexedOpt.Live(Some("x"))) == Chunk(IndexKey("maybe", "x")),
      )
    },
    test("@indexRank widens Int to Long") {
      val ext = IndexExtractor.derived[RankedTicket]
      assertTrue(
        ext.rank(RankedTicket.Open("p-1", 3)).contains(3L),
        ext.rank(RankedTicket.Archived("p-1")).isEmpty,
      )
    },
  )
end IndexExtractorSpec
