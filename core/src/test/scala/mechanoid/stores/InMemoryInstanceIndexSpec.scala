package mechanoid.stores

import zio.*
import zio.test.*
import mechanoid.core.UniqueAliasError
import mechanoid.persistence.Alias

object InMemoryInstanceIndexSpec extends ZIOSpecDefault:

  private val campaign1 = Alias("campaign", "c-1")
  private val campaign2 = Alias("campaign", "c-2")
  private val template1 = Alias("template", "t-1")

  def spec = suite("InMemoryInstanceIndex")(
    suite("bind / resolve")(
      test("bind then resolve returns the instance id") {
        for
          index <- InMemoryInstanceIndex.make[String]
          _     <- index.bind(campaign1, "init-1")
          got   <- index.resolve(campaign1)
        yield assertTrue(got.contains("init-1"))
      },
      test("resolve returns None for unknown alias") {
        for
          index <- InMemoryInstanceIndex.make[String]
          got   <- index.resolve(campaign1)
        yield assertTrue(got.isEmpty)
      },
      test("rebind to the same instance is a no-op") {
        for
          index <- InMemoryInstanceIndex.make[String]
          _     <- index.bind(campaign1, "init-1")
          _     <- index.bind(campaign1, "init-1")
          got   <- index.resolve(campaign1)
        yield assertTrue(got.contains("init-1"))
      },
      test("bind fails when alias is held by a different instance") {
        for
          index  <- InMemoryInstanceIndex.make[String]
          _      <- index.bind(campaign1, "init-1")
          result <- index.bind(campaign1, "init-2").either
        yield result match
          case Left(e: UniqueAliasError) =>
            assertTrue(
              e.namespace == "campaign",
              e.key == "c-1",
              e.heldBy == "init-1",
              e.requested == "init-2",
            )
          case _ => assertTrue(false)
      },
    ),
    suite("bindAll")(
      test("binds a batch of 100 keys") {
        val aliases = Chunk.fromIterable((1 to 100).map(i => Alias("campaign", s"c-$i")))
        for
          index  <- InMemoryInstanceIndex.make[String]
          _      <- index.bindAll(aliases, "init-1")
          first  <- index.resolve(Alias("campaign", "c-1"))
          last   <- index.resolve(Alias("campaign", "c-100"))
          listed <- index.aliasesOf("init-1")
        yield assertTrue(
          first.contains("init-1"),
          last.contains("init-1"),
          listed.size == 100,
        )
        end for
      },
      test("empty bindAll is a no-op") {
        for
          index <- InMemoryInstanceIndex.make[String]
          _     <- index.bindAll(Chunk.empty, "init-1")
          got   <- index.aliasesOf("init-1")
        yield assertTrue(got.isEmpty)
      },
      test("unique clash in a batch fails the whole bindAll") {
        val batch = Chunk(campaign1, campaign2)
        for
          index  <- InMemoryInstanceIndex.make[String]
          _      <- index.bind(campaign2, "init-other")
          result <- index.bindAll(batch, "init-1").either
          c1     <- index.resolve(campaign1)
        yield result match
          case Left(_: UniqueAliasError) => assertTrue(c1.isEmpty)
          case _                         => assertTrue(false)
      },
    ),
    suite("unbind")(
      test("unbind removes a binding") {
        for
          index   <- InMemoryInstanceIndex.make[String]
          _       <- index.bind(campaign1, "init-1")
          removed <- index.unbind(campaign1)
          got     <- index.resolve(campaign1)
        yield assertTrue(removed, got.isEmpty)
      },
      test("unbind of missing alias returns false") {
        for
          index   <- InMemoryInstanceIndex.make[String]
          removed <- index.unbind(campaign1)
        yield assertTrue(!removed)
      },
      test("unbindAll removes a subset") {
        for
          index <- InMemoryInstanceIndex.make[String]
          _     <- index.bindAll(Chunk(campaign1, campaign2, template1), "init-1")
          n     <- index.unbindAll(Chunk(campaign1, campaign2))
          left  <- index.aliasesOf("init-1")
        yield assertTrue(n == 2L, left == Chunk(template1) || left.toSet == Set(template1))
      },
    ),
    suite("aliasesOf / unbindInstance")(
      test("aliasesOf lists bindings for an instance") {
        for
          index <- InMemoryInstanceIndex.make[String]
          _     <- index.bindAll(Chunk(campaign1, template1), "init-1")
          _     <- index.bind(campaign2, "init-2")
          got   <- index.aliasesOf("init-1")
        yield assertTrue(got.toSet == Set(campaign1, template1))
      },
      test("aliasesOf filters by namespace") {
        for
          index <- InMemoryInstanceIndex.make[String]
          _     <- index.bindAll(Chunk(campaign1, template1), "init-1")
          got   <- index.aliasesOf("init-1", Some("campaign"))
        yield assertTrue(got == Chunk(campaign1) || got.toSet == Set(campaign1))
      },
      test("unbindInstance drops every alias for that id") {
        for
          index <- InMemoryInstanceIndex.make[String]
          _     <- index.bindAll(Chunk(campaign1, template1), "init-1")
          _     <- index.bind(campaign2, "init-2")
          n     <- index.unbindInstance("init-1")
          left1 <- index.aliasesOf("init-1")
          left2 <- index.resolve(campaign2)
        yield assertTrue(n == 2L, left1.isEmpty, left2.contains("init-2"))
      },
    ),
  ) @@ TestAspect.timeout(10.seconds)
end InMemoryInstanceIndexSpec
