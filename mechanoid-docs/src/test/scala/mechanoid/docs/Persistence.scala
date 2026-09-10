package mechanoid.docs

import mechanoid.docs.DocZIO.*
import mechanoid.*
import specular.*
import zio.*
import zio.test.*

object Persistence extends MechanoidDocSpecSuite:

  type OrderId = String

  def doc = page("Persistence")(
    section("Event sourcing model")(
      md"""
Writes append after the transition succeeds. Recovery loads an optional snapshot, then replays
later events (transition actions run; entry/producing do not).

```mermaid
flowchart TB
  subgraph writePath [Write path]
    Send[fsm.send] --> Action[Transition action]
    Action --> Append[EventStore.append]
    Append --> Snap[Optional snapshot]
  end
  subgraph recoverPath [Recover path]
    Start[FSMRuntime construct] --> LoadSnap[Load snapshot]
    LoadSnap --> Replay[Replay events after seq]
    Replay --> Ready[Resume]
  end
  class Send,Action,Append,Snap,Start,LoadSnap,Replay,Ready happy
```
""",
      exampleZIO {
        enum OrderState derives Finite:
          case Pending, Paid, Shipped

        enum OrderEvent derives Finite:
          case Pay, Ship

        import OrderState.*, OrderEvent.*

        val machine = Machine(
          assembly[OrderState, OrderEvent](
            Pending via Pay to Paid,
            Paid via Ship to Shipped,
          )
        )

        val orderId: OrderId = "order-persist-1"

        ZIO
          .scoped {
            for
              fsm   <- FSMRuntime(orderId, machine, Pending)
              _     <- fsm.send(Pay)
              _     <- fsm.send(Ship)
              _     <- fsm.saveSnapshot
              state <- fsm.currentState
              seq   <- fsm.lastSequenceNr
            yield (state.toString, seq)
          }
          .provide(
            InMemoryEventStore.layer[OrderId, OrderState, OrderEvent],
            TimeoutStrategy.fiber[OrderId],
            LockingStrategy.optimistic[OrderId],
          )
          .asDoc
      }.assert { case (state, seq) =>
        assertTrue(state == "Shipped") && assertTrue(seq >= 2L)
      },
    ),
    section("Recover after restart")(
      md"""
There is no separate `recover` API: construct `FSMRuntime` again with the same id against the
same `EventStore`. Session one writes history; session two resumes at `Shipped`:
""",
      exampleZIO {
        enum OrderState derives Finite:
          case Pending, Paid, Shipped

        enum OrderEvent derives Finite:
          case Pay, Ship

        import OrderState.*, OrderEvent.*

        val machine = Machine(
          assembly[OrderState, OrderEvent](
            Pending via Pay to Paid,
            Paid via Ship to Shipped,
          )
        )

        val orderId: OrderId = "order-recover-1"

        ZIO.scoped {
          for
            store <- InMemoryEventStore.make[OrderId, OrderState, OrderEvent]()
            _     <- ZIO
              .scoped {
                FSMRuntime(orderId, machine, Pending).flatMap { fsm =>
                  fsm.send(Pay) *> fsm.send(Ship) *> fsm.saveSnapshot
                }
              }
              .provide(
                ZLayer.succeed(store),
                TimeoutStrategy.fiber[OrderId],
                LockingStrategy.optimistic[OrderId],
              )
            recovered <- ZIO
              .scoped {
                FSMRuntime(orderId, machine, Pending).flatMap(_.currentState)
              }
              .provide(
                ZLayer.succeed(store),
                TimeoutStrategy.fiber[OrderId],
                LockingStrategy.optimistic[OrderId],
              )
          yield recovered
        }.asDoc
      }.assert(state => assertTrue(state.toString == "Shipped")),
    ),
    section("Lookup by alias")(
      md"""
The event log is still keyed by instance id. Unique secondary keys (campaign id, template id)
live in `InstanceIndex`: resolve is a primary-key lookup, independent of how many campaigns
an initiative holds.

`FSMRuntime.lookup(alias, machine, initial)` resolves then reconstructs. Unknown aliases
fail with `AliasNotFoundError` (no machine is created). For a GET of current state without
a live runtime: `index.resolve(alias)` then `EventStore.currentState(id)`.

Mark constructor fields with `@alias` (optional namespace; default is the field name) and pass
`AliasExtractor.derived[S]`. Scalars, `Option`, and collections (`List` / `Seq` / `Chunk`) all
work. Values encode with `AliasCodec` (`toString` unless you provide a given):

```scala
enum InitiativeState derives Finite:
  case Draft
  case Live(
    @alias("campaign") campaignIds: List[Long],
    @alias templateId: String,
  )

FSMRuntime(id, machine, Draft, AliasExtractor.derived[InitiativeState])
```
""",
      exampleZIO {
        enum OrderState derives Finite:
          case Pending, Paid, Shipped

        enum OrderEvent derives Finite:
          case Pay, Ship

        import OrderState.*, OrderEvent.*

        val machine = Machine(
          assembly[OrderState, OrderEvent](
            Pending via Pay to Paid,
            Paid via Ship to Shipped,
          )
        )

        val orderId: OrderId = "order-alias-1"
        val campaign         = Alias("campaign", "camp-42")

        ZIO.scoped {
          for
            store <- InMemoryEventStore.make[OrderId, OrderState, OrderEvent]()
            index <- InMemoryInstanceIndex.make[OrderId]
            layers = ZLayer.succeed(store) ++
              ZLayer.succeed[InstanceIndex[OrderId]](index) ++
              TimeoutStrategy.fiber[OrderId] ++
              LockingStrategy.optimistic[OrderId]
            _ <- ZIO
              .scoped {
                FSMRuntime(orderId, machine, Pending).flatMap { fsm =>
                  fsm.send(Pay) *> fsm.saveSnapshot
                }
              }
              .provide(layers)
            _         <- index.bind(campaign, orderId)
            recovered <- ZIO
              .scoped {
                FSMRuntime
                  .lookup[OrderId, OrderState, OrderEvent](campaign, machine, Pending)
                  .flatMap(_.currentState)
              }
              .provide(layers)
          yield recovered
        }.asDoc
      }.assert(state => assertTrue(state.toString == "Paid")),
      md"""
PostgreSQL stores aliases in `fsm_aliases` (`PostgresInstanceIndex`); `PostgresSchema.initialize`
creates that table even when the other tables already exist. IndexedDB uses an `aliases` object
store (database version 2) via `IndexedDbInstanceIndex` / `SharedFSMRuntime.lookup`.
""",
    ),
    section("EventStore and codecs")(
      md"""
Implement `EventStore[Id, S, E]` for your backend (`append`, `loadEvents`, snapshots, …).
`append` must use optimistic locking: atomically check `expectedSeqNr`, then increment.

PostgreSQL ships as `mechanoid-postgres`. Derive JSON codecs with
`import mechanoid.postgres.*` (`finiteJsonCodec` from `Finite`) and initialize schema via
`PostgresSchema.initialize` (see `examples/heartbeat`).
"""
    ),
    section("Browser (Scala.js)")(
      md"""
`mechanoid-web` persists to **IndexedDB** (`IndexedDbEventStore`, `IndexedDbTimeoutStore`,
`IndexedDbInstanceLock`, `IndexedDbInstanceIndex`) and notifies peer tabs over **BroadcastChannel**.
Peers reconstruct `FSMRuntime` from the store (same load-on-demand model as server nodes) so
several tabs share one instance without a server. `SharedFSMRuntime.lookup` resolves a unique
alias then starts that instance.

```scala
libraryDependencies += "rocks.earlyeffect" %%% "mechanoid-web" % "<version>"

import mechanoid.web.*

for
  shared <- SharedFSMRuntime.stores[OrderState, OrderEvent]("my-app")
  fsm    <- SharedFSMRuntime.start(orderId, machine, Pending, shared)
yield fsm
```

Try the live demo under [Browser Persistence](browser-persistence.html).
"""
    ),
    section("Optimistic locking")(
      md"""
Concurrent writers that lose the race see `SequenceConflictError`. Reload and retry, or move up
to [Distributed Coordination](distributed-coordination.html) to prevent conflicts upfront.

Next: [Durable Timeouts](durable-timeouts.html).
"""
    ),
  )
end Persistence
