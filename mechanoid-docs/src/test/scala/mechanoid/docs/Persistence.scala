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

  def doc = page("Persistence")(
    section("Event sourcing model")(
      md"""
```mermaid
flowchart LR
  Send[fsm.send] --> Action[Transition action]
  Action --> Append[EventStore.append]
  Append --> Snap[Optional snapshot]
  Recover[Startup] --> LoadSnap[Load snapshot]
  LoadSnap --> Replay[Replay events after seq]
  Replay --> Ready[Resume]
  class Send,Action,Append,Snap,Recover,LoadSnap,Replay,Ready happy
```

1. Events persist **after** the transition action succeeds
2. State reconstructs by replaying events
3. Snapshots shorten recovery to “events since last snapshot”
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
          .provide(
            InMemoryEventStore.layer[OrderId, OrderState, OrderEvent],
            TimeoutStrategy.fiber[OrderId],
            LockingStrategy.optimistic[OrderId],
          )
          .asDoc
      }.assert { case (state, seq) =>
        assertTrue(state == Shipped) && assertTrue(seq >= 2L)
      },
    ),
    section("EventStore")(
      md"""
Implement `EventStore[Id, S, E]` for your backend (`append`, `loadEvents`, snapshots, …).
`append` must use optimistic locking: atomically check `expectedSeqNr`, then increment.
PostgreSQL ships as `mechanoid-postgres`.
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
