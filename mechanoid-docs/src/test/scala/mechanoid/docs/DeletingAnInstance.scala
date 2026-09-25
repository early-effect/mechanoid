package mechanoid.docs

import java.time.Instant
import mechanoid.docs.DocZIO.*
import mechanoid.*
import mechanoid.persistence.*
import mechanoid.persistence.timeout.TimeoutStore
import mechanoid.stores.*
import specular.*
import zio.*
import zio.test.*

object DeletingAnInstance extends MechanoidDocSpecSuite:

  type OrderId = String

  def doc = page("Deleting an instance")(
    section("An instance is six stores")(
      md"""
The event log is not the instance. Recovery reads the snapshot and replays later events.
`lookup` follows an alias. `find` reads index rows. The sweeper fires timeout rows. The
lock row is the lease other nodes wait on. Any one of those left behind is still the instance.

```mermaid
flowchart LR
  Log[Event log] --> Delete[fsm.delete]
  Snap[Snapshot] --> Delete
  Alias[Aliases] --> Delete
  Index[Index rows] --> Delete
  Time[Timeouts] --> Delete
  Lock[Lock row] --> Delete
  Truncate[deleteEventsTo] --> Log
  class Log,Snap,Alias,Index,Time,Lock,Delete happy
  class Truncate warn
```

`deleteEventsTo` only shortens the log so a snapshot can stand in for the history it replaced.
Cluster leases are not per instance and are not part of delete.
"""
    ),
    section("Truncate is not delete")(
      md"""
After `deleteEventsTo` through the snapshot sequence, constructing the runtime again still
returns `Paid`. `fsm.delete` removes the log and the snapshot. The id can be reused:
`append` with `expectedSeqNr` 0 is sequence 1, a new history.
""",
      exampleZIO {
        enum OrderState derives Finite:
          case Pending, Paid

        enum OrderEvent derives Finite:
          case Pay

        import OrderState.*, OrderEvent.*

        val machine = Machine(
          assembly[OrderState, OrderEvent](
            Pending via Pay to Paid
          )
        )
        val orderId: OrderId = "order-delete-1"

        ZIO.scoped {
          for
            events <- InMemoryEventStore.make[OrderId, OrderState, OrderEvent]()
            index  <- InMemoryInstanceIndex.make[OrderId]
            layers = ZLayer.succeed[EventStore[OrderId, OrderState, OrderEvent]](events) ++
              ZLayer.succeed[InstanceIndex[OrderId]](index) ++
              TimeoutStrategy.fiber[OrderId] ++
              LockingStrategy.optimistic[OrderId]
            _         <- events.append(orderId, Pay, 0L)
            _         <- events.saveSnapshot(FSMSnapshot(orderId, Paid, 1L, Instant.EPOCH))
            _         <- events.deleteEventsTo(orderId, 1L)
            truncated <- FSMRuntime.readState(orderId, machine, Pending).provide(layers)
            _         <- ZIO
              .scoped {
                FSMRuntime(
                  orderId,
                  machine,
                  Pending,
                  AliasExtractor.none[OrderState],
                  IndexExtractor.none[OrderState],
                ).flatMap(_.delete)
              }
              .provide(layers)
            deleted   <- FSMRuntime.readState(orderId, machine, Pending).provide(layers)
            restarted <- events.append(orderId, Pay, 0L)
          yield (truncated.map(_.toString), deleted.isEmpty, restarted)
        }.asDoc
      }.assert { case (truncated, deleted, restarted) =>
        assertTrue(truncated.contains("Paid"), deleted, restarted == 1L)
      },
    ),
    section("Do not hydrate in order to delete")(
      md"""
`FSMRuntime.delete[Id, S, E](id)` wipes an id that is not open on this node. It does not
construct a runtime. Constructing one rebinds aliases, re-arms timeouts, and can write a
sequence-0 snapshot for a timed initial leaf.

`InstanceIndex` is required, even when this id has no aliases. Provide the index the app
writes to (`InMemoryInstanceIndex`, `PostgresInstanceIndex`, or `IndexedDbInstanceIndex`).
Leaving it out would compile a delete that keeps aliases and index rows.

The environment is `EventStore`, `TimeoutStrategy`, `LockingStrategy`, and `InstanceIndex`:

| Service | What delete does with it |
|---------|--------------------------|
| `EventStore[Id, S, E]` | Deletes the log and the snapshot together |
| `TimeoutStrategy[Id]` | Purges timeouts. Store failures fail the delete. `cancel` on Goto stays best-effort |
| `InstanceIndex[Id]` | `unbindInstance`: aliases and index rows |
| `LockingStrategy[Id]` | Optimistic has no row. Distributed deletes the lock row for any holder |

`S` and `E` are the same pins as `readState`.
""",
      exampleZIO {
        enum OrderState derives Finite:
          case Pending, Paid

        enum OrderEvent derives Finite:
          case Pay

        import OrderEvent.*

        val orderId: OrderId = "order-delete-2"
        val campaign         = Alias("campaign", "camp-42")

        (for
          events   <- InMemoryEventStore.make[OrderId, OrderState, OrderEvent]()
          index    <- InMemoryInstanceIndex.make[OrderId]
          timeouts <- InMemoryTimeoutStore.make[OrderId]
          lock     <- InMemoryFSMInstanceLock.make[OrderId]
          now      <- Clock.instant
          strategy <- DistributedLockingStrategy.make(lock)
          _        <- events.append(orderId, Pay, 0L)
          _        <- events.saveSnapshot(FSMSnapshot(orderId, OrderState.Paid, 1L, now))
          _        <- index.bind(campaign, orderId)
          _        <- index.bindIndexes(Chunk(IndexKey("assignee", "me")), orderId, IndexMeta("Paid", now, now, None))
          _        <- timeouts.schedule(orderId, "tick", 1, 1L, now.plusMillis(50))
          _        <- lock.tryAcquire(orderId, "other-node", 30.seconds, now)
          _        <- FSMRuntime
            .delete[OrderId, OrderState, OrderEvent](orderId)
            .provide(
              ZLayer.succeed[EventStore[OrderId, OrderState, OrderEvent]](events),
              ZLayer.succeed[InstanceIndex[OrderId]](index),
              ZLayer.succeed[TimeoutStrategy[OrderId]](DurableTimeoutStrategy.make(timeouts)),
              ZLayer.succeed[LockingStrategy[OrderId]](strategy),
            )
          snap  <- events.loadSnapshot(orderId)
          alias <- index.resolve(campaign)
          keys  <- index.indexesOf(orderId)
          timer <- timeouts.get(orderId)
          held  <- lock.get(orderId, now)
          seq   <- events.highestSequenceNr(orderId)
        yield (snap.isEmpty, alias.isEmpty, keys.isEmpty, timer.isEmpty, held.isEmpty, seq)).asDoc
      }.assert { case (snap, alias, keys, timer, held, seq) =>
        assertTrue(snap, alias, keys, timer, held, seq == 0L)
      },
    ),
    section("Two doors")(
      md"""
| Call | When |
|------|------|
| `fsm.delete` | You have the runtime. Stops it first and waits for in-flight `.producing`, so a later `send` cannot append. In-memory `append` ignores `expectedSeqNr` and would recreate the log if the runtime were still running. |
| `FSMRuntime.delete[Id, S, E](id)` | Nothing is open on this node. Same wipe. Does not stop some other runtime object. If one is open, call `delete` on it. |

Wipe order: purge timeouts, unbind aliases and index rows, delete the log and snapshot, then
force-release the lock row (any holder, including an expired row). Each step succeeds when the
id is already gone, so retry finishes a crash between steps. This is not one transaction across
every table. Events and the snapshot commit together. Aliases and index rows commit together
inside `unbindInstance`. `LockedFSMRuntime` also drops the lock it holds outside the inner
strategy, which is often optimistic.

Delete does not take `InstanceMailbox`. The permit is one per id, and `FSMRuntime.session`
already holds it around the block, so `fsm.delete` there is same-node exclusive with the
sweeper. Next to a sweeper from outside a session:

```scala
mailbox.run(id)(FSMRuntime.delete[OrderId, OrderState, OrderEvent](id))
```

Delete does not wait out another node's `withLock`. That critical section can append once.
The lock row is still removed. Delete does not touch cluster leases.
"""
    ),
  )
end DeletingAnInstance
