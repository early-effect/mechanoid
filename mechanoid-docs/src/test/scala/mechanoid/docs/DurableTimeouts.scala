package mechanoid.docs

import mechanoid.docs.DocZIO.*
import mechanoid.docs.platform.NamedTimeoutDemo
import mechanoid.docs.platform.NamedTimeoutDemoUi.{Campaign, machine as campaignMachine}
import mechanoid.*
import specular.*
import zio.*
import zio.test.*

object DurableTimeouts extends DocSpec:

  type OrderId = String

  def doc = page("Durable Timeouts")(
    section("Why durable")(
      md"""
Fiber timeouts are fast and local. If the node dies while an FSM sits in a timed state, that
fiber is gone. Durable timeouts store deadlines in a `TimeoutStore` so another node's sweeper
can fire them.

```mermaid
flowchart LR
  NodeA[Node A schedules] --> Store[TimeoutStore]
  NodeA -.->|dies| Gone[Fiber gone]
  Store --> Sweeper[TimeoutSweeper]
  Sweeper --> NodeB[Node B fires timeout event]
  class NodeA,Store,Sweeper,NodeB happy
  class Gone warn
```
"""
    ),
    section("TimeoutStrategy")(
      md"""
| Strategy | Layer | Survives restart |
|----------|-------|------------------|
| Fiber | `TimeoutStrategy.fiber[Id]` | No |
| Durable | `TimeoutStrategy.durable[Id]` (+ `TimeoutStore`) | Yes |

Schedule with durable strategy, then send the timeout event the sweeper would fire (DocSpecs use
a live clock; unit tests can `TestClock.adjust` fiber timeouts instead):
""",
      exampleZIO {
        enum OrderState derives Finite:
          case Pending, Started, Done, Cancelled

        enum OrderEvent derives Finite:
          case StartPayment, Complete, PaymentTimeout

        import OrderState.*, OrderEvent.*

        val machine = Machine(
          assembly[OrderState, OrderEvent](
            (Pending via StartPayment to Started) @@ Aspect.timeout(1.hour, PaymentTimeout),
            Started via Complete to Done,
            Started via PaymentTimeout to Cancelled,
          )
        )

        val orderId: OrderId = "order-timeout-1"

        ZIO
          .scoped {
            for
              fsm   <- FSMRuntime(orderId, machine, Pending)
              _     <- fsm.send(StartPayment)
              _     <- fsm.send(PaymentTimeout)
              state <- fsm.currentState
            yield state
          }
          .provide(
            InMemoryEventStore.layer[OrderId, OrderState, OrderEvent],
            ZLayer.fromZIO(InMemoryTimeoutStore.make[OrderId]),
            TimeoutStrategy.durable[OrderId],
            LockingStrategy.optimistic[OrderId],
          )
          .asDoc
      }.assert(state => assertTrue(state.toString == "Cancelled")),
    ),
    section("Named timeouts on one leaf")(
      md"""
Stack `@@ Aspect.timeout(event)(deadline)` to arm independent cadences on the same leaf. The name
defaults to `Finite.nameOf(event)`. Stay on one timeout re-arms only that name; Goto cancels
every name for the instance.

The panel is a campaign: **Go live** arms `DailyCheck` (3s, Stay) and `EndCycle` (9s, Goto
Ended). Wait for DailyCheck, or fire it: the weekly clock keeps running. EndCycle (or wait it
out) cancels both.
""",
      exampleZIO {
        ZIO.succeed(campaignMachine.timeoutsFor(Campaign.Live).map(_.name).toSet)
      }.assert(names => assertTrue(names == Set("DailyCheck", "EndCycle"))),
      exampleIO {
        NamedTimeoutDemo.ui
      }.interactive.assert(ui => assertTrue(ui.toString.nonEmpty)),
    ),
    section("TimeoutSweeper")(
      md"""
A background sweeper:

1. Queries expired, unclaimed timeouts (several rows per instance is allowed)
2. Claims each timeout by `(instanceId, name)`
3. Fires when `stateHash` still matches **and** that name is still configured on the current leaf
4. Looks up the event from `timeoutConfigForState` by name and `runtime.send`s it
5. Marks complete for that name only (`sequenceNr` must match so a Stay re-arm is not deleted)

Use `TimeoutSweeperConfig` for interval, jitter, batch size, claim duration, and `nodeId`.
Optional **leader election** via `LeaseStore` keeps a single active sweeper to reduce DB load.

See `examples/heartbeat` for a full sweeper alongside `FSMRuntime`, and [Testing](testing.html)
for the DocSpec vs TestClock choice.

Next: [Distributed Coordination](distributed-coordination.html).
"""
    ),
  )
end DurableTimeouts
