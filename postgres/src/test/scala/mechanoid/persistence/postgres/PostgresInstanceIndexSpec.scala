package mechanoid.persistence.postgres

import saferis.{sql, Transactor}
import zio.*
import zio.test.*
import mechanoid.PostgresTestContainer
import mechanoid.core.*
import mechanoid.*
import mechanoid.persistence.{Alias, IndexQuery, InstanceIndex}
import mechanoid.stores.InstanceIndexLaws

enum PgTicket derives Finite:
  case Open(@index assignee: String, @index project: String)
  case Archived(@index assignee: String, @index project: String)

enum PgInitiative derives Finite:
  case Live(@alias campaign: String, @alias template: String)

object PostgresInstanceIndexSpec extends ZIOSpecDefault:

  val xaLayer    = PostgresTestContainer.DataSourceProvider.transactor
  val indexLayer = xaLayer >+> PostgresInstanceIndex.layer

  private def unique(prefix: String): String = s"$prefix-${java.util.UUID.randomUUID()}"

  private def wipe(xa: Transactor) =
    xa.run(sql"TRUNCATE fsm_aliases, fsm_indexes".dml)

  def spec = (
    suite("PostgresInstanceIndex")(
      test("bind then resolve returns the instance id") {
        val alias = Alias.of[PgInitiative].campaign(unique("c"))
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
          got   <- index.resolve(Alias.of[PgInitiative].campaign(unique("missing")))
        yield assertTrue(got.isEmpty)
      },
      test("rebind to the same instance is a no-op") {
        val alias = Alias.of[PgInitiative].campaign(unique("c"))
        val id    = unique("init")
        for
          index <- ZIO.service[InstanceIndex[String]]
          _     <- index.bind(alias, id)
          _     <- index.bind(alias, id)
          got   <- index.resolve(alias)
        yield assertTrue(got.contains(id))
      },
      test("bind fails when alias is held by a different instance") {
        val alias = Alias.of[PgInitiative].campaign(unique("c"))
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
        val aliases = Chunk.fromIterable((1 to 50).map(i => Alias.of[PgInitiative].campaign(unique(s"c$i"))))
        for
          index  <- ZIO.service[InstanceIndex[String]]
          _      <- index.bindAll(aliases, id)
          listed <- index.aliasesOf(id, Some("campaign"))
          first  <- index.resolve(aliases.head)
        yield assertTrue(listed.size == 50, first.contains(id))
      },
      test("unique clash in a batch rolls back earlier inserts") {
        val taken  = Alias.of[PgInitiative].campaign(unique("taken"))
        val fresh  = Alias.of[PgInitiative].campaign(unique("fresh"))
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
      test("only(Archived) does not walk Open rows") {
        val person = unique("p")
        val openN  = 2000
        for
          index <- ZIO.service[InstanceIndex[String]]
          _     <- ZIO.foreachDiscard(0 until openN) { i =>
            index.bindIndexes(
              Chunk(mechanoid.persistence.IndexKey("assignee", person)),
              s"open-$i",
              mechanoid.persistence.IndexMeta("Open", java.time.Instant.EPOCH, java.time.Instant.EPOCH, None),
            )
          }
          _ <- ZIO.foreachDiscard(0 until 3) { i =>
            index.bindIndexes(
              Chunk(mechanoid.persistence.IndexKey("assignee", person)),
              s"arch-$i",
              mechanoid.persistence.IndexMeta("Archived", java.time.Instant.EPOCH, java.time.Instant.EPOCH, None),
            )
          }
          page <- index.find(
            IndexQuery.of[PgTicket].assignee(person).only(state[PgTicket.Archived]).limit(8)
          )
          n <- index.count(IndexQuery.of[PgTicket].assignee(person).only(state[PgTicket.Archived]))
        yield assertTrue(page.items.size == 3, n == 3L, page.items.forall(_.stateName == "Archived"))
        end for
      } @@ TestAspect.timeout(60.seconds),
      test("unbind and unbindInstance") {
        val id       = unique("init")
        val campaign = Alias.of[PgInitiative].campaign(unique("c"))
        val template = Alias.of[PgInitiative].template(unique("t"))
        for
          index   <- ZIO.service[InstanceIndex[String]]
          _       <- index.bindAll(Chunk(campaign, template), id)
          removed <- index.unbind(campaign)
          left    <- index.aliasesOf(id)
          n       <- index.unbindInstance(id)
          after   <- index.resolve(template)
        yield assertTrue(removed, left.toSet == Set(template), n == 1L, after.isEmpty)
      },
      InstanceIndexLaws(
        ZIO.service[InstanceIndex[String]],
        distractorN = 40,
      ) @@ TestAspect.sequential @@ TestAspect.samples(15),
    ) @@ TestAspect.sequential @@ TestAspect.before {
      ZIO.serviceWithZIO[Transactor](wipe)
    } @@ TestAspect.withLiveClock
  ).provideShared(indexLayer)
end PostgresInstanceIndexSpec
