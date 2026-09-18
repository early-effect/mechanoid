package mechanoid.persistence

import mechanoid.*
import mechanoid.machine.state
import zio.Chunk
import zio.test.*

enum BuilderTicket derives Finite:
  case Open(@index assignee: String, @index project: String)
  case Archived(@index assignee: String, @index project: String)

enum BuilderInitiative derives Finite:
  case Live(@alias campaign: String)

object IndexQueryBuilderSpec extends ZIOSpecDefault:

  def spec = suite("IndexQuery.of")(
    test("member select encodes AliasCodec value") {
      val q = IndexQuery.of[BuilderTicket].assignee("p-1").toQuery[String]
      assertTrue(q.key == IndexKey("assignee", "p-1"), q.filter == IndexFilter.All)
    },
    test("only(state[Archived]) encodes Finite simple name") {
      val q = IndexQuery.of[BuilderTicket].assignee("p-1").only(state[BuilderTicket.Archived]).toQuery[String]
      assertTrue(q.filter == IndexFilter.Only(Set("Archived")))
    },
    test("require.project ANDs another key") {
      val q = IndexQuery.of[BuilderTicket].assignee("p-1").require.project("alpha").toQuery[String]
      assertTrue(q.require == Chunk(IndexKey("project", "alpha")))
    },
    test("unknown member does not compile") {
      val result = typeCheck("""
        import mechanoid.*
        import mechanoid.persistence.IndexQuery
        enum T derives Finite:
          case Open(@index assignee: String)
        IndexQuery.of[T].nope("x")
      """)
      assertZIO(result)(Assertion.isLeft)
    },
    test("only(state[leaf not in S]) does not compile") {
      val result = typeCheck("""
        import mechanoid.*
        import mechanoid.machine.state
        import mechanoid.persistence.IndexQuery
        enum T derives Finite:
          case Open(@index assignee: String)
        enum Other derives Finite:
          case Archived
        IndexQuery.of[T].assignee("p").only(state[Other.Archived])
      """)
      assertZIO(result)(Assertion.isLeft)
    },
    test("Alias.of uses the member name") {
      val a = Alias.of[BuilderInitiative].campaign("c-1")
      assertTrue(a == Alias("campaign", "c-1"))
    },
    test("only two leaves encodes both Finite names") {
      val q = IndexQuery
        .of[BuilderTicket]
        .assignee("p-1")
        .only(state[BuilderTicket.Open], state[BuilderTicket.Archived])
        .toQuery[String]
      assertTrue(q.filter == IndexFilter.Only(Set("Open", "Archived")))
    },
    test("ns(ident) encodes the ident name") {
      val assignee = "unused-binding"
      val _        = assignee
      val q        = IndexQuery.of[BuilderTicket].ns(assignee)("p-1").toQuery[String]
      assertTrue(q.key == IndexKey("assignee", "p-1"))
    },
    test("ns(string literal) does not compile") {
      val result = typeCheck("""
        import mechanoid.*
        import mechanoid.persistence.IndexQuery
        enum T derives Finite:
          case Open(@index assignee: String)
        IndexQuery.of[T].ns("assignee")("x")
      """)
      assertZIO(result)(Assertion.isLeft)
    },
    test("Alias.of unknown member does not compile") {
      val result = typeCheck("""
        import mechanoid.*
        import mechanoid.persistence.Alias
        enum T derives Finite:
          case Live(@alias campaign: String)
        Alias.of[T].typo("x")
      """)
      assertZIO(result)(Assertion.isLeft)
    },
  )
end IndexQueryBuilderSpec
