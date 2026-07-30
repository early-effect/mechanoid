package mechanoid.web

import zio.*
import zio.json.*
import zio.test.*
import mechanoid.*
import mechanoid.persistence.*
import mechanoid.persistence.timeout.*
import mechanoid.persistence.lock.*
import java.time.Instant
import scala.scalajs.js

object IndexedDbStoresSpec extends ZIOSpecDefault:

  // Install fake-indexeddb before any IDB access.
  private val installFakeIdb: UIO[Unit] =
    ZIO.succeed {
      js.Dynamic.global.require("fake-indexeddb/auto")
      ()
    }

  enum TestState derives Finite, JsonCodec:
    case Pending, Paid, Shipped

  enum TestEvent derives Finite, JsonCodec:
    case Pay, Ship

  import TestState.*, TestEvent.*

  private def uniqueDb: UIO[String] =
    ZIO.succeed(s"mechanoid-test-${scala.util.Random.alphanumeric.take(12).mkString}")

  def spec = suite("IndexedDb stores")(
    suite("EventStore")(
      test("append and load events") {
        for
          _      <- installFakeIdb
          dbName <- uniqueDb
          store  <- IndexedDbEventStore.make[TestState, TestEvent](dbName)
          seq1   <- store.append("o1", Pay, 0L)
          seq2   <- store.append("o1", Ship, 1L)
          events <- store.loadEvents("o1").runCollect
        yield assertTrue(seq1 == 1L, seq2 == 2L, events.map(_.event) == Chunk(Pay, Ship))
      },
      test("optimistic conflict on wrong expectedSeqNr") {
        for
          _      <- installFakeIdb
          dbName <- uniqueDb
          store  <- IndexedDbEventStore.make[TestState, TestEvent](dbName)
          _      <- store.append("o1", Pay, 0L)
          result <- store.append("o1", Ship, 0L).either
        yield assertTrue(result.isLeft)
      },
      test("snapshot round-trip") {
        for
          _      <- installFakeIdb
          dbName <- uniqueDb
          store  <- IndexedDbEventStore.make[TestState, TestEvent](dbName)
          now    <- Clock.instant
          _      <- store.saveSnapshot(FSMSnapshot("o1", Paid, 1L, now))
          snap   <- store.loadSnapshot("o1")
        yield assertTrue(snap.exists(_.state == Paid), snap.exists(_.sequenceNr == 1L))
      },
    ),
    suite("TimeoutStore")(
      test("schedule get and queryExpired") {
        for
          _       <- installFakeIdb
          dbName  <- uniqueDb
          store   <- IndexedDbTimeoutStore.make(dbName)
          now     <- Clock.instant
          _       <- store.schedule("o1", 1, 1L, now.minusSeconds(1))
          got     <- store.get("o1")
          expired <- store.queryExpired(10, now)
        yield assertTrue(got.isDefined, expired.exists(_.instanceId == "o1"))
      },
      test("claim and complete") {
        for
          _       <- installFakeIdb
          dbName  <- uniqueDb
          store   <- IndexedDbTimeoutStore.make(dbName)
          now     <- Clock.instant
          _       <- store.schedule("o1", 1, 2L, now.minusSeconds(1))
          claimed <- store.claim("o1", "node-a", 30.seconds, now)
          done    <- store.complete("o1", 2L)
          after   <- store.get("o1")
        yield assertTrue(
          claimed match
            case ClaimResult.Claimed(_) => true
            case _                      => false
          ,
          done,
          after.isEmpty,
        )
      },
    ),
    suite("InstanceLock")(
      test("acquire and release") {
        for
          _        <- installFakeIdb
          dbName   <- uniqueDb
          lock     <- IndexedDbInstanceLock.make(dbName)
          now      <- Clock.instant
          first    <- lock.tryAcquire("o1", "node-a", 30.seconds, now)
          busy     <- lock.tryAcquire("o1", "node-b", 30.seconds, now)
          released <- first match
            case LockResult.Acquired(token) => lock.release(token)
            case _                          => ZIO.succeed(false)
        yield assertTrue(first.isAcquired, busy.isBusy, released)
      }
    ),
    suite("reconstruct via SharedFSMRuntime stores")(
      test("second runtime recovers peer appends") {
        for
          _      <- installFakeIdb
          dbName <- uniqueDb
          store  <- IndexedDbEventStore.make[TestState, TestEvent](dbName)
          machine = Machine(
            assembly[TestState, TestEvent](
              Pending via Pay to Paid,
              Paid via Ship to Shipped,
            )
          )
          _ <- ZIO
            .scoped {
              FSMRuntime("order-1", machine, Pending).flatMap { fsm =>
                fsm.send(Pay) *> fsm.saveSnapshot
              }
            }
            .provide(
              ZLayer.succeed[EventStore[String, TestState, TestEvent]](store),
              TimeoutStrategy.fiber[String],
              LockingStrategy.optimistic[String],
            )
          state <- ZIO
            .scoped {
              FSMRuntime("order-1", machine, Pending).flatMap(_.currentState)
            }
            .provide(
              ZLayer.succeed[EventStore[String, TestState, TestEvent]](store),
              TimeoutStrategy.fiber[String],
              LockingStrategy.optimistic[String],
            )
        yield assertTrue(state == Paid)
      }
    ),
  ) @@ TestAspect.sequential @@ TestAspect.timeout(30.seconds)
end IndexedDbStoresSpec
