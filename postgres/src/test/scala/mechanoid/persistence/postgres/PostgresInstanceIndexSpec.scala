package mechanoid.persistence.postgres

import zio.*
import zio.test.*
import mechanoid.PostgresTestContainer
import mechanoid.core.*
import mechanoid.persistence.{Alias, InstanceIndex}

object PostgresInstanceIndexSpec extends ZIOSpecDefault:

  val xaLayer    = PostgresTestContainer.DataSourceProvider.transactor
  val indexLayer = xaLayer >>> PostgresInstanceIndex.layer

  private def unique(prefix: String): String = s"$prefix-${java.util.UUID.randomUUID()}"

  def spec = suite("PostgresInstanceIndex")(
    test("bind then resolve returns the instance id") {
      val alias = Alias("campaign", unique("c"))
      val id    = unique("init")
      for
        index <- ZIO.service[InstanceIndex[String]]
        _     <- index.bind(alias, id)
        got   <- index.resolve(alias)
      yield assertTrue(got.contains(id))
    },
    test("resolve returns None for unknown alias") {
      for
        index <- ZIO.service[InstanceIndex[String]]
        got   <- index.resolve(Alias("campaign", unique("missing")))
      yield assertTrue(got.isEmpty)
    },
    test("rebind to the same instance is a no-op") {
      val alias = Alias("campaign", unique("c"))
      val id    = unique("init")
      for
        index <- ZIO.service[InstanceIndex[String]]
        _     <- index.bind(alias, id)
        _     <- index.bind(alias, id)
        got   <- index.resolve(alias)
      yield assertTrue(got.contains(id))
    },
    test("bind fails when alias is held by a different instance") {
      val alias = Alias("campaign", unique("c"))
      val id1   = unique("init")
      val id2   = unique("init")
      for
        index  <- ZIO.service[InstanceIndex[String]]
        _      <- index.bind(alias, id1)
        result <- index.bind(alias, id2).either
      yield result match
        case Left(e: UniqueAliasError) =>
          assertTrue(e.heldBy == id1, e.requested == id2)
        case _ => assertTrue(false)
    },
    test("bindAll of many keys then aliasesOf") {
      val id      = unique("init")
      val aliases = Chunk.fromIterable((1 to 50).map(i => Alias("campaign", unique(s"c$i"))))
      for
        index  <- ZIO.service[InstanceIndex[String]]
        _      <- index.bindAll(aliases, id)
        listed <- index.aliasesOf(id, Some("campaign"))
        first  <- index.resolve(aliases.head)
      yield assertTrue(listed.size == 50, first.contains(id))
    },
    test("unique clash in a batch rolls back earlier inserts") {
      val taken  = Alias("campaign", unique("taken"))
      val fresh  = Alias("campaign", unique("fresh"))
      val holder = unique("other")
      val id     = unique("init")
      for
        index  <- ZIO.service[InstanceIndex[String]]
        _      <- index.bind(taken, holder)
        result <- index.bindAll(Chunk(fresh, taken), id).either
        got    <- index.resolve(fresh)
      yield result match
        case Left(_: UniqueAliasError) => assertTrue(got.isEmpty)
        case _                         => assertTrue(false)
    },
    test("unbind and unbindInstance") {
      val id       = unique("init")
      val campaign = Alias("campaign", unique("c"))
      val template = Alias("template", unique("t"))
      for
        index   <- ZIO.service[InstanceIndex[String]]
        _       <- index.bindAll(Chunk(campaign, template), id)
        removed <- index.unbind(campaign)
        left    <- index.aliasesOf(id)
        n       <- index.unbindInstance(id)
        after   <- index.resolve(template)
      yield assertTrue(removed, left.toSet == Set(template), n == 1L, after.isEmpty)
    },
  ).provideLayer(indexLayer)
end PostgresInstanceIndexSpec
