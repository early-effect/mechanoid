package mechanoid.compiletime

import zio.test.*

object IndexExtractorRejectSpec extends ZIOSpecDefault:

  def spec = suite("IndexExtractor derived rejects")(
    test("both @alias and @index on one field is rejected") {
      val result = typeCheck("""
        import mechanoid.*
        import mechanoid.persistence.IndexExtractor
        enum S derives Finite:
          case Live(@alias @index id: String)
        IndexExtractor.derived[S]
      """)
      assertZIO(result)(Assertion.isLeft)
    },
    test("two @indexUpdated fields on one leaf is rejected") {
      val result = typeCheck("""
        import java.time.Instant
        import mechanoid.*
        import mechanoid.persistence.IndexExtractor
        enum S derives Finite:
          case Live(@indexUpdated a: Instant, @indexUpdated b: Instant)
        IndexExtractor.derived[S]
      """)
      assertZIO(result)(Assertion.isLeft)
    },
    test("@indexCreated on a non-Instant field is rejected") {
      val result = typeCheck("""
        import mechanoid.*
        import mechanoid.persistence.IndexExtractor
        enum S derives Finite:
          case Live(@indexCreated n: Int)
        IndexExtractor.derived[S]
      """)
      assertZIO(result)(Assertion.isLeft)
    },
    test("@indexRank on a non-numeric field is rejected") {
      val result = typeCheck("""
        import mechanoid.*
        import mechanoid.persistence.IndexExtractor
        enum S derives Finite:
          case Live(@indexRank title: String)
        IndexExtractor.derived[S]
      """)
      assertZIO(result)(Assertion.isLeft)
    },
    test("two @indexRank fields on one leaf is rejected") {
      val result = typeCheck("""
        import mechanoid.*
        import mechanoid.persistence.IndexExtractor
        enum S derives Finite:
          case Live(@indexRank a: Int, @indexRank b: Long)
        IndexExtractor.derived[S]
      """)
      assertZIO(result)(Assertion.isLeft)
    },
    test("plain @index Instant field is accepted") {
      val result = typeCheck("""
        import java.time.Instant
        import mechanoid.*
        import mechanoid.persistence.IndexExtractor
        enum S derives Finite:
          case Live(@index assignee: String, @indexCreated c: Instant, @indexUpdated e: Instant)
        IndexExtractor.derived[S]
      """)
      assertZIO(result)(Assertion.isRight)
    },
  )
end IndexExtractorRejectSpec
