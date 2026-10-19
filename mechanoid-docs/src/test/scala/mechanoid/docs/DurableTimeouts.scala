package mechanoid.docs

import mechanoid.docs.DocZIO.*
import mechanoid.docs.platform.NamedTimeoutDemo
import mechanoid.docs.platform.NamedTimeoutDemoUi.{Campaign, machine as campaignMachine}
import mechanoid.*
import mermoid.Mermaid
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
""",
      illustration(
        DocsDiagram.paint(Mermaid("""flowchart LR
          |  NodeA[Node A schedules] --> Store[TimeoutStore]
          |  NodeA -.->|dies| Gone[Fiber gone]
          |  Store --> Sweeper[TimeoutSweeper]
          |  Sweeper --> NodeB[Node B fires timeout event]
          |  class NodeA,Store,Sweeper,NodeB happy
          |  class Gone warn
          |""".stripMargin))
      ).assert(ui => assertTrue(ui.toString.contains("mermoid-root"), ui.toString.contains("TimeoutSweeper"))),
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
defaults to `Finite.nameOf(event)`. A stay re-arms the timer that fired. It also re-arms a
`FromPayload` timer when the new payload computes a different instant. `After` and `At` stay
put, and Goto cancels every name for the instance.

The panel is a campaign: **Go live** arms `DailyCheck` (3s `After`, Stay) and `EndCycle` (9s, Goto
Ended). Wait for DailyCheck, or fire it: the weekly clock keeps running because that stay does not
reset an `After` timer. EndCycle (or wait it out) cancels both.
""",
      exampleZIO {
        ZIO.succeed(campaignMachine.timeoutsFor(Campaign.Live).map(_.name).toSet)
      }.assert(names => assertTrue(names == Set("DailyCheck", "EndCycle"))),
      exampleZIO {
        enum Leaf derives Finite:
          case Idle
          case Open(due: Long)

        enum Ev derives Finite:
          case Begin
          case Edit(due: Long)
          case Watch
          case Beat

        import Leaf.*, Ev.*

        val epoch = java.time.Instant.parse("2026-01-01T00:00:00Z")
        val near  = epoch.plusSeconds(5)
        val far   = epoch.plusSeconds(100)

        def dueOf(state: Leaf): java.time.Instant =
          state match
            case Open(due) => java.time.Instant.ofEpochSecond(due)
            case Idle      => epoch.plusSeconds(1000000)

        val machine = Machine(
          assembly[Leaf, Ev](
            (Idle via Begin to Open(0L)) @@
              Aspect.timeout(Watch)(dueOf) @@
              Aspect.timeout(Beat)(1.hour),
            (state[Leaf.Open] via event[Edit]).to(stay)((s, e) => s.copy(due = e.due)),
          )
        )

        ZIO.scoped {
          for
            events <- InMemoryEventStore.makeUnbounded[String, Leaf, Ev]
            store  <- InMemoryTimeoutStore.make[String]
            durable = DurableTimeoutStrategy.make[String](store)
            fsm <- FSMRuntime("stay-due", machine, Open(far.getEpochSecond)).provideSome[Scope](
              ZLayer.succeed(events) ++
                ZLayer.succeed(durable) ++
                LockingStrategy.optimistic[String]
            )
            beatBefore <- store.get("stay-due", "Beat")
            _          <- fsm.send(Edit(near.getEpochSecond))
            moved      <- store.get("stay-due", "Watch")
            _          <- fsm.send(Edit(near.getEpochSecond))
            same       <- store.get("stay-due", "Watch")
            beatAfter  <- store.get("stay-due", "Beat")
          yield (
            moved.map(_.deadline),
            same.map(_.deadline),
            beatBefore.map(_.deadline),
            beatAfter.map(_.deadline),
          )
        }.asDoc
      }.assert { (moved, same, beatBefore, beatAfter) =>
        val near = java.time.Instant.parse("2026-01-01T00:00:00Z").plusSeconds(5)
        assertTrue(
          moved == Some(near),
          same == Some(near),
          beatBefore == beatAfter,
          beatBefore.isDefined,
        )
      },
      exampleIO {
        NamedTimeoutDemo.ui
      }.interactive.assert(ui => assertTrue(ui.toString.contains("mermoid-ascent"), ui.toString.contains("Go live"))),
    ),
    section("TimeoutSweeper")(
      md"""
Servers are ephemeral. Any node may claim an expired row; the claim is what fires once.
A failed `send`, including `SequenceConflictError` and `InvalidTransitionError`, releases
that claim so a later sweep can deliver. The row is completed when `send` succeeds, the
leaf hash no longer matches, the name is no longer armed, or the instance was never
persisted. The failure we do not want is a machine left in a timed leaf whose timeout
never arrives.

`fsm.delete` purges every timeout row for the id, so the sweeper cannot fire a machine that
is gone. `cancel` on Goto is not that. See [Deleting an instance](deleting-an-instance.html).

Load-on-demand (REST / many instances): reconstruct the **claimed** id, send, drop.

```scala
TimeoutSweeper.make(
  config,
  timeoutStore,
  id => FSMRuntime.existing(id, machine, initial),
)
```

Heartbeat (one long-lived instance) uses `TimeoutSweeper.pinned(config, store, runtime)`.
It claims only that runtime's instance id.

Flow:

1. Query expired, unclaimed timeouts (several rows per instance is allowed)
2. Claim each timeout by `(instanceId, name)`
3. Open a scoped runtime for that id (`existing`, or the pinned runtime)
4. Fire when `stateHash` still matches **and** that name is still configured on the current leaf
5. Look up the event from `timeoutConfigForState` by name and `send`
6. Complete that name only (`sequenceNr` is the claimed row, so a Stay re-arm is not deleted).
   A failed `send`, store error, or reconstruct error **releases** so a later sweep retries.

Use `TimeoutSweeperConfig` for interval, jitter, batch size, claim duration, `nodeId`,
and `withDelivery`. Optional **leader election** via `LeaseStore` keeps a single active
sweeper to reduce DB load. Multi-sweeper + atomic claims is the REST default.

Same-node HTTP and the sweeper share `InstanceMailbox` so reconstruct+send for one id
cannot interleave in-process. Cross-node exclusivity is still `LockingStrategy`.

Use durable timeouts on reconstruct. Fiber timeouts leak across request/sweeper scopes.

See `examples/heartbeat` for a pinned sweeper, and [Testing](testing.html)
for the DocSpec vs TestClock choice.

Next: [Distributed Coordination](distributed-coordination.html).
"""
    ),
  )
end DurableTimeouts
