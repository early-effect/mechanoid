package mechanoid.web

import org.scalajs.dom
import zio.*
import scala.scalajs.js

/** BroadcastChannel peer notify for multi-tab FSM sync.
  *
  * Posts the instance id after local writes; peers reconstruct [[mechanoid.runtime.FSMRuntime]] from the EventStore
  * (same load-on-demand model as server nodes).
  */
final class TabSynchronizer private (
    channel: dom.BroadcastChannel,
    fiberRef: Ref[Option[Fiber[Nothing, Unit]]],
):

  /** Post that this tab wrote for `instanceId`. */
  def publish(instanceId: String): UIO[Unit] =
    ZIO.succeed(channel.postMessage(instanceId))

  /** Listen for peer writes and run `handler` with the instance id. */
  def listen(handler: String => UIO[Unit]): UIO[Unit] =
    for
      _       <- stopListening
      runtime <- ZIO.runtime[Any]
      fiber   <- ZIO
        .async[Any, Nothing, Unit] { _ =>
          channel.onmessage = (event: dom.MessageEvent) =>
            val id = event.data.asInstanceOf[String]
            Unsafe.unsafe { implicit u =>
              runtime.unsafe.fork(handler(id))
            }
            ()
        }
        .forever
        .forkDaemon
      _ <- fiberRef.set(Some(fiber))
    yield ()

  /** Stop the listener fiber; keep the channel open for publish. */
  def stopListening: UIO[Unit] =
    fiberRef.get.flatMap {
      case Some(f) =>
        ZIO.succeed(channel.onmessage = null.asInstanceOf[js.Function1[dom.MessageEvent, Any]]) *>
          f.interrupt.unit <* fiberRef.set(None)
      case None =>
        ZIO.unit
    }

  /** Tear down listener and close the channel (scope finalizer). */
  def stop: UIO[Unit] =
    stopListening <* ZIO.succeed(channel.close())
end TabSynchronizer

object TabSynchronizer:

  def make(channelName: String = "mechanoid-sync"): UIO[TabSynchronizer] =
    for
      ref <- Ref.make(Option.empty[Fiber[Nothing, Unit]])
      ch = new dom.BroadcastChannel(channelName)
    yield new TabSynchronizer(ch, ref)

  def layer(channelName: String = "mechanoid-sync"): ULayer[TabSynchronizer] =
    ZLayer.scoped {
      make(channelName).flatMap { sync =>
        ZIO.acquireRelease(ZIO.succeed(sync))(_.stop)
      }
    }
end TabSynchronizer
