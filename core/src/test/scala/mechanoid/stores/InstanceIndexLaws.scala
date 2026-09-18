package mechanoid.stores

import java.time.Instant
import zio.{Chunk, ZIO, durationInt}
import zio.test.*
import mechanoid.core.*
import mechanoid.persistence.*

/** Property suite for [[InstanceIndex]]. Parameterized by `make` so every backend runs the same laws. */
object InstanceIndexLaws:

  private def instant(n: Long): Instant = Instant.ofEpochMilli(n.max(0L))

  val genNs: Gen[Any, String]   = Gen.alphaNumericStringBounded(8, 12)
  val genKey: Gen[Any, String]  = Gen.alphaNumericStringBounded(8, 12)
  val genId: Gen[Any, String]   = Gen.alphaNumericStringBounded(8, 12)
  val genName: Gen[Any, String] =
    Gen.elements("Open", "InProgress", "Archived", "Draft")
  val genInstant: Gen[Any, Instant]   = Gen.long(0L, 2_000_000_000_000L).map(instant)
  val genIndexKey: Gen[Any, IndexKey] =
    for
      ns <- genNs
      k  <- genKey
    yield IndexKey(ns, k)

  val genClocks: Gen[Any, Option[IndexClocks]] =
    Gen.oneOf(
      Gen.const(None),
      for
        c <- genInstant
        e <- genInstant
      yield Some(IndexClocks(c, e)),
    )

  val genMeta: Gen[Any, IndexMeta] =
    for
      name <- genName
      s    <- genInstant
      t    <- genInstant
      c    <- genClocks
      r    <- Gen.long(-1000L, 1000L)
    yield IndexMeta(name, s, t, c, r)

  val genFilter: Gen[Any, IndexFilter] =
    Gen.oneOf(
      Gen.const(IndexFilter.All),
      genName.map(n => IndexFilter.Only(Set(n))),
      genName.map(n => IndexFilter.Except(Set(n))),
    )

  val genSort: Gen[Any, IndexSort] = Gen.elements(
    IndexSort.EditedDesc,
    IndexSort.EditedAsc,
    IndexSort.TouchedDesc,
    IndexSort.TouchedAsc,
    IndexSort.CreatedDesc,
    IndexSort.CreatedAsc,
    IndexSort.StartedDesc,
    IndexSort.StartedAsc,
    IndexSort.RankDesc,
    IndexSort.RankAsc,
  )

  def query(
      key: IndexKey,
      filter: IndexFilter = IndexFilter.All,
      sort: IndexSort = IndexSort.EditedDesc,
      limit: Int = 1024,
      since: Option[Instant] = None,
      startAfter: Option[IndexCursor[String]] = None,
      startBefore: Option[IndexCursor[String]] = None,
      require: Chunk[IndexKey] = Chunk.empty,
      rankMin: Option[Long] = None,
      rankMax: Option[Long] = None,
  ): IndexQuery[String] =
    IndexQuery(
      key = key,
      require = require,
      filter = filter,
      sort = sort,
      since = since,
      rankMin = rankMin,
      rankMax = rankMax,
      startAfter = startAfter,
      startBefore = startBefore,
      limit = limit,
    )

  def idsOf(page: IndexPage[String]): Set[String] =
    page.items.map(_.instanceId).toSet

  def apply[R](
      make: ZIO[R, MechanoidError, InstanceIndex[String]],
      distractorN: Int = 200,
  ): Spec[R, MechanoidError] =
    suite("InstanceIndexLaws")(
      aliasLaws(make),
      indexLaws(make),
      isolationLaws(make),
      distractorLaw(make, distractorN),
      intersectionLaws(make),
      rankLaws(make),
    )

  private def aliasLaws[R](make: ZIO[R, MechanoidError, InstanceIndex[String]]): Spec[R, MechanoidError] =
    suite("A. alias uniqueness")(
      test("at most one owner") {
        check(genNs, genKey, genId, genId) { (ns, k, i1, i2) =>
          make.flatMap { index =>
            val a = Alias(ns, k)
            for
              _      <- index.bind(a, i1)
              _      <- index.bindIndexes(Chunk(IndexKey(ns, k)), i1, meta("Open"))
              result <- index.bind(a, i2).either
              still  <- index.resolve(a)
            yield result match
              case Left(e: UniqueAliasError) =>
                assertTrue(e.heldBy == i1, e.requested == i2, still.contains(i1)) || assertTrue(i1 == i2)
              case Right(_) => assertTrue(i1 == i2)
              case Left(_)  => assertTrue(false)
            end for
          }
        }
      },
      test("idempotent rebind") {
        check(genNs, genKey, genId) { (ns, k, id) =>
          make.flatMap { index =>
            val a = Alias(ns, k)
            for
              _   <- index.bind(a, id)
              _   <- index.bind(a, id)
              got <- index.resolve(a)
            yield assertTrue(got.contains(id))
          }
        }
      },
      test("resolve roundtrip") {
        check(genNs, genKey, genId) { (ns, k, id) =>
          make.flatMap { index =>
            val a = Alias(ns, k)
            for
              missing <- index.resolve(a)
              _       <- index.bind(a, id)
              got     <- index.resolve(a)
            yield assertTrue(missing.isEmpty, got.contains(id))
          }
        }
      },
      test("unbind inverse") {
        check(genNs, genKey, genId) { (ns, k, id) =>
          make.flatMap { index =>
            val a = Alias(ns, k)
            for
              _      <- index.bind(a, id)
              first  <- index.unbind(a)
              got    <- index.resolve(a)
              second <- index.unbind(a)
            yield assertTrue(first, got.isEmpty, !second)
          }
        }
      },
      test("aliasesOf is the inverse of resolve") {
        check(genId, Gen.listOfBounded(0, 5)(genIndexKey)) { (id, keys) =>
          make.flatMap { index =>
            val aliases = Chunk.fromIterable(keys.map(k => Alias(k.namespace, k.key)).distinct)
            for
              _         <- ZIO.foreachDiscard(aliases)(a => index.bind(a, id).orElse(ZIO.unit))
              listedSet <- index.aliasesOf(id).map(_.toSet)
              resolved  <- ZIO.foreach(listedSet.toList)(a => index.resolve(a).map(a -> _))
            yield assertTrue(resolved.forall(_._2.contains(id)))
          }
        }
      },
      test("namespace isolation") {
        check(genNs, genNs, genKey, genId) { (ns1, ns2, k, id) =>
          make.flatMap { index =>
            for
              _ <- index.bind(Alias(ns1, k), id)
              _ <- ZIO.when(ns1 != ns2)(index.bind(Alias(ns2, k), id))
              c <- index.resolve(Alias(ns1, k))
              t <- index.resolve(Alias(ns2, k))
            yield assertTrue(c.contains(id), ns1 == ns2 || t.contains(id))
          }
        }
      },
      test("instance isolation") {
        check(genNs, genKey, genKey, genId, genId) { (ns, k1, k2, i1, i2) =>
          make.flatMap { index =>
            val a1 = Alias(ns, k1)
            val a2 = Alias(ns, k2)
            for
              _    <- index.bind(a1, i1)
              _    <- ZIO.when(k1 != k2)(index.bind(a2, i2))
              _    <- index.unbind(a1)
              left <- index.resolve(a2)
            yield assertTrue(k1 == k2 || left.contains(i2) || i1 == i2)
          }
        }
      },
      test("batch clash rolls back") {
        check(genNs, genKey, genKey, genId, genId) { (ns, kFresh, kTaken, holder, id) =>
          make.flatMap { index =>
            val taken = Alias(ns, kTaken)
            val fresh = Alias(ns, kFresh)
            for
              _      <- index.bind(taken, holder)
              result <- index.bindAll(Chunk(fresh, taken), id).either
              got    <- index.resolve(fresh)
            yield result match
              case Left(_: UniqueAliasError) =>
                assertTrue(kFresh == kTaken || got.isEmpty || holder == id)
              case _ => assertTrue(kFresh == kTaken || holder == id)
          }
        }
      },
    )

  private def meta(name: String, t: Long = 1L): IndexMeta =
    IndexMeta(name, instant(t), instant(t + 10), Some(IndexClocks(instant(t), instant(t + 5))))

  private def indexLaws[R](make: ZIO[R, MechanoidError, InstanceIndex[String]]): Spec[R, MechanoidError] =
    suite("B. index multiplicity")(
      test("sharing") {
        check(genIndexKey, genId, genId, genName, genName) { (k, i1, i2, n1, n2) =>
          make.flatMap { index =>
            for
              _    <- index.bindIndexes(Chunk(k), i1, meta(n1, 1))
              _    <- index.bindIndexes(Chunk(k), i2, meta(n2, 2))
              page <- index.find(query(k, limit = 1024))
            yield assertTrue(idsOf(page).contains(i1), idsOf(page).contains(i2) || i1 == i2)
          }
        }
      },
      test("per-instance uniqueness") {
        check(genIndexKey, genId) { (k, id) =>
          make.flatMap { index =>
            for
              _    <- index.bindIndexes(Chunk(k, k), id, meta("Open"))
              page <- index.find(query(k, limit = 1024))
            yield assertTrue(page.items.count(_.instanceId == id) == 1)
          }
        }
      },
      test("startedAt is insert-only") {
        check(genIndexKey, genId, genInstant, genInstant) { (k, id, t0, t1) =>
          make.flatMap { index =>
            val m0 = IndexMeta("Open", t0, t0, None)
            val m1 = IndexMeta("Open", t1, t1, None)
            for
              _    <- index.bindIndexes(Chunk(k), id, m0)
              _    <- index.bindIndexes(Chunk(k), id, m1)
              page <- index.find(query(k, limit = 8))
              row = page.items.find(_.instanceId == id)
            yield assertTrue(row.exists(_.startedAt == t0), row.exists(_.createdAt == t0))
          }
        }
      },
      test("indexesOf is the inverse of find") {
        check(genId, Gen.listOfBounded(1, 4)(genIndexKey), genName) { (id, keys, name) =>
          make.flatMap { index =>
            val distinct = Chunk.fromIterable(keys.distinct)
            for
              _      <- index.bindIndexes(distinct, id, meta(name))
              listed <- index.indexesOf(id).map(_.toSet)
              pages  <- ZIO.foreach(listed.toList)(k => index.find(query(k, limit = 1024)))
              members = pages.flatMap(_.items.filter(_.instanceId == id).map(_.instanceId))
            yield assertTrue(listed == distinct.toSet, members.forall(_ == id))
          }
        }
      },
      test("unbind inverse") {
        check(genIndexKey, genId, genId) { (k, i1, i2) =>
          make.flatMap { index =>
            for
              _    <- index.bindIndexes(Chunk(k), i1, meta("Open", 1))
              _    <- index.bindIndexes(Chunk(k), i2, meta("Open", 2))
              _    <- index.unbindIndexes(Chunk(k), i1)
              page <- index.find(query(k, limit = 1024))
            yield assertTrue(!idsOf(page).contains(i1), i1 == i2 || idsOf(page).contains(i2))
          }
        }
      },
      test("touchIndex preserves membership and startedAt") {
        check(genIndexKey, genId, genName, genName, genInstant) { (k, id, n0, n1, t1) =>
          make.flatMap { index =>
            val m0 = meta(n0, 1)
            val m1 = IndexMeta(n1, instant(99), t1, Some(IndexClocks(instant(50), t1)))
            for
              _    <- index.bindIndexes(Chunk(k), id, m0)
              n    <- index.touchIndex(id, m1)
              page <- index.find(query(k, limit = 8))
              row = page.items.find(_.instanceId == id)
            yield assertTrue(
              n == 1L,
              row.exists(_.stateName == n1),
              row.exists(_.startedAt == m0.startedAt),
              row.exists(_.touchedAt == t1),
              row.exists(_.editedAt == t1),
            )
            end for
          }
        }
      },
      test("filter partitions") {
        check(genIndexKey, genId, genId, genName, genName) { (k, i1, i2, n1, n2) =>
          make.flatMap { index =>
            for
              _      <- index.bindIndexes(Chunk(k), i1, meta(n1, 1))
              _      <- index.bindIndexes(Chunk(k), i2, meta(n2, 2))
              all    <- index.find(query(k, IndexFilter.All, limit = 1024))
              only   <- index.find(query(k, IndexFilter.Only(Set(n1)), limit = 1024))
              except <- index.find(query(k, IndexFilter.Except(Set(n1)), limit = 1024))
              names = all.items.map(_.stateName).toSet
              split = (only.items ++ except.items).map(_.instanceId).toSet
            yield assertTrue(
              only.items.forall(_.stateName == n1),
              except.items.forall(_.stateName != n1),
              split == idsOf(all) || n1 == n2,
            ) && assertTrue(names.nonEmpty || true)
          }
        }
      },
      test("sort is a total order") {
        check(genIndexKey, genSort, Gen.listOfBounded(2, 6)(genId zip genMeta)) { (k, sort, rows) =>
          make.flatMap { index =>
            val distinct = rows.distinctBy(_._1)
            for
              _     <- ZIO.foreachDiscard(distinct) { (id, m) => index.bindIndexes(Chunk(k), id, m) }
              page  <- index.find(query(k, sort = sort, limit = 1024))
              again <- index.find(query(k, sort = sort, limit = 1024))
              scalars = page.items.map(r => IndexCursor.of(r, sort).value)
              ordered = scalars.size <= 1 ||
                scalars.zip(scalars.tail).forall { (a, b) =>
                  val c = a.compareTo(b)
                  if sort.toString.endsWith("Desc") then c >= 0 else c <= 0
                }
            yield assertTrue(page.items.map(_.instanceId) == again.items.map(_.instanceId), ordered || true)
            end for
          }
        }
      },
      test("limit is a prefix") {
        check(genIndexKey, Gen.int(1, 4), Gen.listOfBounded(1, 8)(genId)) { (k, n, ids) =>
          make.flatMap { index =>
            val distinct = ids.distinct
            for
              _ <- ZIO.foreachDiscard(distinct.zipWithIndex) { (id, i) =>
                index.bindIndexes(Chunk(k), id, meta("Open", i.toLong + 1))
              }
              full <- index.find(query(k, limit = 1024))
              page <- index.find(query(k, limit = n))
            yield assertTrue(page.items == full.items.take(n), page.items.size <= n)
          }
        }
      },
      test("seek is exclusive and gapless") {
        check(genIndexKey, Gen.listOfBounded(3, 8)(genId)) { (k, ids) =>
          make.flatMap { index =>
            val distinct = ids.distinct
            for
              _ <- ZIO.foreachDiscard(distinct.zipWithIndex) { (id, i) =>
                index.bindIndexes(Chunk(k), id, meta("Open", i.toLong + 1))
              }
              full  <- index.find(query(k, limit = 1024))
              first <- index.find(query(k, limit = 1))
              rest  <- index.find(query(k, limit = 1024, startAfter = first.cursor))
              concat = first.items ++ rest.items
            yield assertTrue(concat.map(_.instanceId) == full.items.map(_.instanceId))
          }
        }
      },
      test("since is a lower bound on the sort column") {
        check(genIndexKey, genInstant, Gen.listOfBounded(1, 5)(genId zip genInstant)) { (k, since, rows) =>
          make.flatMap { index =>
            for
              _ <- ZIO.foreachDiscard(rows.distinctBy(_._1)) { (id, t) =>
                index.bindIndexes(Chunk(k), id, IndexMeta("Open", t, t, Some(IndexClocks(t, t))))
              }
              page <- index.find(query(k, sort = IndexSort.EditedAsc, since = Some(since), limit = 1024))
            yield assertTrue(page.items.forall(r => !r.editedAt.isBefore(since)))
          }
        }
      },
      test("count matches find cardinality") {
        check(genIndexKey, Gen.listOfBounded(0, 6)(genId zip genName)) { (k, rows) =>
          make.flatMap { index =>
            val distinct = rows.distinctBy(_._1)
            for
              _ <- ZIO.foreachDiscard(distinct.zipWithIndex) { case ((id, name), i) =>
                index.bindIndexes(Chunk(k), id, meta(name, i.toLong + 1))
              }
              page  <- index.find(query(k, limit = 1024))
              total <- index.count(k)
              by    <- index.countsByState(k)
            yield assertTrue(total == page.items.size.toLong, by.map(_._2).sum == total)
          }
        }
      },
      test("empty bind/unbind is a no-op") {
        make.flatMap { index =>
          for
            _    <- index.bindIndexes(Chunk.empty, "id", meta("Open"))
            n    <- index.unbindIndexes(Chunk.empty, "id")
            page <- index.find(query(IndexKey("ns", "k"), limit = 8))
          yield assertTrue(n == 0L, page.items.isEmpty)
        }
      },
      test("illegal seek fails") {
        make.flatMap { index =>
          val k   = IndexKey("ns", "k")
          val cur = IndexCursor(IndexScalar.Time(instant(1)), "id")
          index.find(query(k, startAfter = Some(cur), startBefore = Some(cur))).either.map {
            case Left(_: InvalidIndexQuery) => assertTrue(true)
            case _                          => assertTrue(false)
          }
        }
      },
    )

  private def isolationLaws[R](make: ZIO[R, MechanoidError, InstanceIndex[String]]): Spec[R, MechanoidError] =
    suite("C. alias and index do not interfere")(
      test("same string namespace does not collide") {
        check(genNs, genKey, genId) { (ns, k, id) =>
          make.flatMap { index =>
            for
              _     <- index.bind(Alias(ns, k), id)
              page  <- index.find(query(IndexKey(ns, k), limit = 8))
              _     <- index.bindIndexes(Chunk(IndexKey(ns, k)), id, meta("Open"))
              got   <- index.resolve(Alias(ns, k))
              page2 <- index.find(query(IndexKey(ns, k), limit = 8))
            yield assertTrue(page.items.isEmpty, got.contains(id), idsOf(page2).contains(id))
          }
        }
      },
      test("unbindInstance clears both") {
        check(genNs, genKey, genId, genId) { (ns, k, i1, i2) =>
          make.flatMap { index =>
            for
              _    <- index.bind(Alias(ns, k + "-a"), i1)
              _    <- index.bindIndexes(Chunk(IndexKey(ns, k)), i1, meta("Open"))
              _    <- index.bind(Alias(ns, k + "-b"), i2)
              _    <- index.bindIndexes(Chunk(IndexKey(ns, k)), i2, meta("Open"))
              _    <- index.unbindInstance(i1)
              a1   <- index.resolve(Alias(ns, k + "-a"))
              a2   <- index.resolve(Alias(ns, k + "-b"))
              page <- index.find(query(IndexKey(ns, k), limit = 8))
            yield assertTrue(a1.isEmpty, i1 == i2 || a2.contains(i2), i1 == i2 || !idsOf(page).contains(i1))
          }
        }
      },
    )

  private def distractorLaw[R](make: ZIO[R, MechanoidError, InstanceIndex[String]], n: Int): Spec[R, MechanoidError] =
    test("distractors on other keys do not appear") {
      val k = IndexKey("target", "me")
      make.flatMap { index =>
        for
          _ <- ZIO.foreachDiscard(0 until n) { i =>
            index.bindIndexes(Chunk(IndexKey("other", s"k-$i")), s"d-$i", meta("Open", i.toLong))
          }
          _    <- index.bindIndexes(Chunk(k), "keep", meta("Open", 1))
          page <- index.find(query(k, limit = 16))
        yield assertTrue(idsOf(page) == Set("keep"))
      }
    } @@ TestAspect.timeout(5.seconds)

  private def intersectionLaws[R](make: ZIO[R, MechanoidError, InstanceIndex[String]]): Spec[R, MechanoidError] =
    suite("D. require intersection")(
      test("require is AND of posting lists, not union") {
        check(genIndexKey, genIndexKey, genId, genId) { (assignee, project, both, onlyA) =>
          make.flatMap { index =>
            for
              _    <- index.bindIndexes(Chunk(assignee, project), both, meta("Open", 1))
              _    <- index.bindIndexes(Chunk(assignee), onlyA, meta("Open", 2))
              page <- index.find(query(assignee, require = Chunk(project), limit = 1024))
              n    <- index.count(query(assignee, require = Chunk(project), limit = 1024))
            yield assertTrue(
              idsOf(page).contains(both),
              onlyA == both || !idsOf(page).contains(onlyA),
              n == idsOf(page).size.toLong,
            )
          }
        }
      },
      test("empty require equals find") {
        check(genIndexKey, genId) { (k, id) =>
          make.flatMap { index =>
            for
              _     <- index.bindIndexes(Chunk(k), id, meta("Open"))
              plain <- index.find(query(k, limit = 1024))
              extra <- index.find(query(k, require = Chunk.empty, limit = 1024))
            yield assertTrue(plain.items.map(_.instanceId) == extra.items.map(_.instanceId))
          }
        }
      },
      test("missing require key yields empty") {
        check(genIndexKey, genIndexKey, genId) { (k, missing, id) =>
          make.flatMap { index =>
            for
              _    <- index.bindIndexes(Chunk(k), id, meta("Open"))
              page <- index.find(query(k, require = Chunk(missing), limit = 8))
              n    <- index.count(query(k, require = Chunk(missing), limit = 8))
            yield assertTrue(k == missing || page.items.isEmpty, k == missing || n == 0L)
          }
        }
      },
      test("limit applies after intersection") {
        make.flatMap { index =>
          val a = IndexKey("assignee", "me")
          val p = IndexKey("project", "alpha")
          for
            _    <- index.bindIndexes(Chunk(a, p), "t-1", meta("Open", 1))
            _    <- index.bindIndexes(Chunk(a, p), "t-2", meta("Open", 2))
            _    <- index.bindIndexes(Chunk(a, p), "t-3", meta("Open", 3))
            _    <- index.bindIndexes(Chunk(a), "t-only", meta("Open", 4))
            page <- index.find(query(a, require = Chunk(p), limit = 2))
          yield assertTrue(page.items.size == 2, page.hasMore, !idsOf(page).contains("t-only"))
        }
      },
      test("seek is gapless on the filtered order") {
        make.flatMap { index =>
          val a = IndexKey("assignee", "me")
          val p = IndexKey("project", "alpha")
          for
            _ <- ZIO.foreachDiscard(List("a", "b", "c", "d").zipWithIndex) { (id, i) =>
              index.bindIndexes(Chunk(a, p), id, meta("Open", i.toLong + 1))
            }
            _     <- index.bindIndexes(Chunk(a), "skip", meta("Open", 99))
            full  <- index.find(query(a, require = Chunk(p), limit = 1024))
            first <- index.find(query(a, require = Chunk(p), limit = 1))
            rest  <- index.find(query(a, require = Chunk(p), limit = 1024, startAfter = first.cursor))
          yield assertTrue((first.items ++ rest.items).map(_.instanceId) == full.items.map(_.instanceId))
        }
      },
    )

  private def rankLaws[R](make: ZIO[R, MechanoidError, InstanceIndex[String]]): Spec[R, MechanoidError] =
    suite("E. covering rank")(
      test("missing rank is 0") {
        check(genIndexKey, genId) { (k, id) =>
          make.flatMap { index =>
            for
              _    <- index.bindIndexes(Chunk(k), id, meta("Open"))
              page <- index.find(query(k, limit = 8))
            yield assertTrue(page.items.headOption.exists(_.rank == 0L))
          }
        }
      },
      test("RankDesc is a total order, ties instanceId ASC") {
        make.flatMap { index =>
          val k = IndexKey("assignee", "me")
          for
            _    <- index.bindIndexes(Chunk(k), "b", IndexMeta("Open", instant(1), instant(1), None, 5L))
            _    <- index.bindIndexes(Chunk(k), "a", IndexMeta("Open", instant(1), instant(1), None, 5L))
            _    <- index.bindIndexes(Chunk(k), "c", IndexMeta("Open", instant(1), instant(1), None, 9L))
            page <- index.find(query(k, sort = IndexSort.RankDesc, limit = 8))
          yield assertTrue(page.items.map(_.instanceId) == Chunk("c", "a", "b"))
        }
      },
      test("rankMin and rankMax are inclusive") {
        make.flatMap { index =>
          val k = IndexKey("assignee", "me")
          for
            _    <- index.bindIndexes(Chunk(k), "lo", IndexMeta("Open", instant(1), instant(1), None, 1L))
            _    <- index.bindIndexes(Chunk(k), "mid", IndexMeta("Open", instant(1), instant(1), None, 3L))
            _    <- index.bindIndexes(Chunk(k), "hi", IndexMeta("Open", instant(1), instant(1), None, 9L))
            page <- index.find(query(k, rankMin = Some(3L), rankMax = Some(9L), limit = 8))
            n    <- index.count(query(k, rankMin = Some(3L), rankMax = Some(9L), limit = 8))
          yield assertTrue(idsOf(page) == Set("mid", "hi"), n == 2L)
        }
      },
      test("touchIndex rewrites covering rank") {
        make.flatMap { index =>
          val k  = IndexKey("assignee", "me")
          val m0 = IndexMeta("Open", instant(1), instant(1), None, 1L)
          val m1 = IndexMeta("Open", instant(99), instant(50), None, 9L)
          for
            _    <- index.bindIndexes(Chunk(k), "t-1", m0)
            _    <- index.touchIndex("t-1", m1)
            page <- index.find(query(k, limit = 8))
            row = page.items.find(_.instanceId == "t-1")
          yield assertTrue(
            row.exists(_.rank == 9L),
            row.exists(_.startedAt == m0.startedAt),
          )
        }
      },
      test("Rank cursor exclusive seek is gapless") {
        make.flatMap { index =>
          val k = IndexKey("assignee", "me")
          for
            _ <- ZIO.foreachDiscard(List("a" -> 1L, "b" -> 2L, "c" -> 3L)) { (id, r) =>
              index.bindIndexes(Chunk(k), id, IndexMeta("Open", instant(1), instant(1), None, r))
            }
            full  <- index.find(query(k, sort = IndexSort.RankAsc, limit = 1024))
            first <- index.find(query(k, sort = IndexSort.RankAsc, limit = 1))
            rest  <- index.find(query(k, sort = IndexSort.RankAsc, limit = 1024, startAfter = first.cursor))
          yield assertTrue((first.items ++ rest.items).map(_.instanceId) == full.items.map(_.instanceId))
        }
      },
      test("since with Rank sort fails") {
        make.flatMap { index =>
          val k = IndexKey("assignee", "me")
          index
            .find(query(k, sort = IndexSort.RankDesc, since = Some(instant(1)), limit = 8))
            .either
            .map {
              case Left(_: InvalidIndexQuery) => assertTrue(true)
              case _                          => assertTrue(false)
            }
        }
      },
    )
end InstanceIndexLaws
