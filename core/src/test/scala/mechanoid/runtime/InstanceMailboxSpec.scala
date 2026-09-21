package mechanoid.runtime

import zio.*
import zio.test.*

object InstanceMailboxSpec extends ZIOSpecDefault:

  def spec = suite("InstanceMailbox")(
    test("same id serializes; the second waiter sees the first's write") {
      for
        box     <- InstanceMailbox.make[String]
        order   <- Ref.make(List.empty[Int])
        started <- Promise.make[Nothing, Unit]
        first   <- box
          .run("a") {
            started.succeed(()) *> ZIO.sleep(1.hour) *> order.update(_ :+ 1)
          }
          .fork
        _      <- started.await
        second <- box.run("a")(order.update(_ :+ 2)).fork
        _      <- TestClock.adjust(1.hour)
        _      <- first.join
        _      <- second.join
        seen   <- order.get
      yield assertTrue(seen == List(1, 2))
    },
    test("different ids do not wait on each other") {
      for
        box     <- InstanceMailbox.make[String]
        overlap <- Ref.make(0)
        max     <- Ref.make(0)
        entered <- Promise.make[Nothing, Unit]
        enter = overlap.updateAndGet(_ + 1).flatMap { n =>
          max.update(m => math.max(m, n)) *>
            ZIO.when(n == 2)(entered.succeed(())).unit
        }
        leave = overlap.update(_ - 1)
        work  = enter *> ZIO.sleep(1.hour) *> leave
        f1   <- box.run("a")(work).fork
        f2   <- box.run("b")(work).fork
        _    <- entered.await
        peak <- max.get
        _    <- TestClock.adjust(1.hour)
        _    <- f1.join
        _    <- f2.join
      yield assertTrue(peak == 2)
    },
  )
end InstanceMailboxSpec
