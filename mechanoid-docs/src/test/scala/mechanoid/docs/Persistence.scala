package mechanoid.docs

import mechanoid.docs.DocZIO.*
import mechanoid.*
import specular.*
import zio.*
import zio.test.*

object Persistence extends MechanoidDocSpecSuite:

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

  type OrderId = String

  private val runtimeLayers =
    InMemoryEventStore.layer[OrderId, OrderState, OrderEvent] ++
      TimeoutStrategy.fiber[OrderId] ++
      LockingStrategy.optimistic[OrderId]

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
            yield (state, seq)
          }
          .provide(runtimeLayers)
          .asDoc
      }.assert { case (state, seq) =>
        assertTrue(state == Shipped) && assertTrue(seq >= 2L)
      },
    ),
    section("Recover after restart")(
      md"""
There is no separate `recover` API: construct `FSMRuntime` again with the same id against the
same `EventStore`. Session one writes history; session two resumes at `Shipped`:
""",
      exampleZIO {
        val orderId: OrderId = "order-recover-1"
        ZIO.scoped {
          for
            store <- InMemoryEventStore.make[OrderId, OrderState, OrderEvent]()
            _ <- ZIO
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
      }.assert(state => assertTrue(state == Shipped)),
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
    section("Optimistic locking")(
      md"""
Concurrent writers that lose the race see `SequenceConflictError`. Reload and retry, or move up
to [Distributed Coordination](distributed-coordination.html) to prevent conflicts upfront.

Next: [Durable Timeouts](durable-timeouts.html).
"""
    ),
  )
end Persistence
