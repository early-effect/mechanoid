package mechanoid.web

import zio.*
import zio.json.*
import zio.test.*
import mechanoid.*
import scala.scalajs.js

/** Multi-tab reconstruct proof in Scala.js (jsdom + fake-indexeddb + BroadcastChannel polyfill). */
object SharedFSMRuntimeSpec extends ZIOSpecDefault:

  private val installEnv: UIO[Unit] =
    ZIO.succeed {
      js.Dynamic.global.require("fake-indexeddb/auto")
      js.Dynamic.global.eval(
        """
        (function () {
          if (typeof globalThis.BroadcastChannel !== 'undefined') return;
          var reg = Object.create(null);
          globalThis.BroadcastChannel = function (name) {
            this.name = name;
            this.onmessage = null;
            if (!reg[name]) reg[name] = [];
            reg[name].push(this);
          };
          globalThis.BroadcastChannel.prototype.postMessage = function (data) {
            var peers = reg[this.name] || [];
            for (var i = 0; i < peers.length; i++) {
              var peer = peers[i];
              if (peer !== this && typeof peer.onmessage === 'function') {
                peer.onmessage({ data: data });
              }
            }
          };
          globalThis.BroadcastChannel.prototype.close = function () {
            var peers = reg[this.name] || [];
            var idx = peers.indexOf(this);
            if (idx >= 0) peers.splice(idx, 1);
          };
        })();
        """
      )
      ()
    }

  enum TestState derives Finite, JsonCodec:
    case Pending, Paid, Shipped

  enum TestEvent derives Finite, JsonCodec:
    case Pay, Ship

  import TestState.*, TestEvent.*

  private val machine = Machine(
    assembly[TestState, TestEvent](
      Pending via Pay to Paid,
      Paid via Ship to Shipped,
    )
  )

  private def uniqueName: UIO[String] =
    ZIO.succeed(s"mech-${scala.util.Random.alphanumeric.take(12).mkString}")

  def spec = suite("SharedFSMRuntime")(
    test("peer tab reconstructs after BroadcastChannel notify") {
      for
        _       <- installEnv
        dbName  <- uniqueName
        channel <- uniqueName.map(n => s"$n-sync")
        seen    <- Ref.make(Option.empty[TestState])
        result  <- ZIO.scoped {
          for
            storesA <- SharedFSMRuntime.stores[TestState, TestEvent](dbName, channel)
            fsmA    <- SharedFSMRuntime.start("order-1", machine, Pending, storesA)
            _       <- fsmA.send(Pay)

            storesB <- SharedFSMRuntime.stores[TestState, TestEvent](dbName, channel)
            fsmB    <- SharedFSMRuntime.start(
              "order-1",
              machine,
              Pending,
              storesB,
              onState = s => seen.set(Some(s)).unit,
            )
            afterConstruct <- fsmB.currentState

            // Append also publishes on the channel; peer reconstructs to Shipped
            _ <- fsmA.send(Ship)
            _ <- seen.get
              .repeatUntil(_.contains(Shipped))
              .timeoutFail("peer did not reconstruct to Shipped")(3.seconds)
            afterNotify <- fsmB.currentState
          yield assertTrue(afterConstruct == Paid, afterNotify == Shipped)
        }
      yield result
    }
  ) @@ TestAspect.sequential @@ TestAspect.timeout(30.seconds)
end SharedFSMRuntimeSpec
