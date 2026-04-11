package mechanoid.runtime

import zio.*
import zio.test.*
import mechanoid.*

object FSMRuntimeAspectSpec extends ZIOSpecDefault:

  enum TestState derives Finite:
    case A, B, C

  enum TestEvent derives Finite:
    case E1, E2

  import TestState.*
  import TestEvent.*

  val testMachine = Machine(
    assembly[TestState, TestEvent](
      A via E1 to B,
      B via E2 to C,
    )
  )

  def spec = suite("FSMRuntimeAspectSpec")(
    suite("FSMRuntimeAspect.transform")(
      test("creates aspect that transforms runtime") {
        val aspect = FSMRuntimeAspect.transform[Unit, TestState, TestEvent](identity)
        for
          runtime <- (testMachine.start(A) @@ aspect)
          state   <- runtime.currentState
        yield assertTrue(state == A)
      },
      test("transform function is applied to runtime") {
        // Create a tracking wrapper
        var transformCalled = false
        val aspect          = FSMRuntimeAspect.transform[Unit, TestState, TestEvent] { rt =>
          transformCalled = true
          rt
        }
        for _ <- (testMachine.start(A) @@ aspect)
        yield assertTrue(transformCalled)
      },
    ),
    suite("FSMRuntimeAspect.transformZIO")(
      test("creates effectful aspect") {
        val aspect = FSMRuntimeAspect.transformZIO[Unit, TestState, TestEvent, Any] { rt =>
          ZIO.succeed(rt)
        }
        for
          runtime <- (testMachine.start(A) @@ aspect)
          state   <- runtime.currentState
        yield assertTrue(state == A)
      },
      test("transformZIO effect is executed") {
        for
          ref <- Ref.make(false)
          aspect = FSMRuntimeAspect.transformZIO[Unit, TestState, TestEvent, Any] { rt =>
            ref.set(true).as(rt)
          }
          _      <- (testMachine.start(A) @@ aspect)
          called <- ref.get
        yield assertTrue(called)
      },
    ),
    suite("FSMRuntimeAspect.identity")(
      test("identity aspect passes through unchanged") {
        val aspect = FSMRuntimeAspect.identity[Unit, TestState, TestEvent]
        for
          runtime <- (testMachine.start(A) @@ aspect)
          _       <- runtime.send(E1)
          state   <- runtime.currentState
        yield assertTrue(state == B)
      }
    ),
    suite("FSMRuntimeAspect.andThen / >>>")(
      test("composes two aspects in order") {
        for
          order <- Ref.make(List.empty[Int])
          aspect1 = FSMRuntimeAspect.transformZIO[Unit, TestState, TestEvent, Any] { rt =>
            order.update(_ :+ 1).as(rt)
          }
          aspect2 = FSMRuntimeAspect.transformZIO[Unit, TestState, TestEvent, Any] { rt =>
            order.update(_ :+ 2).as(rt)
          }
          combined = aspect1 >>> aspect2
          _      <- (testMachine.start(A) @@ combined)
          result <- order.get
        yield assertTrue(result == List(1, 2))
      },
      test("andThen is alias for >>>") {
        for
          order <- Ref.make(List.empty[Int])
          aspect1 = FSMRuntimeAspect.transformZIO[Unit, TestState, TestEvent, Any] { rt =>
            order.update(_ :+ 1).as(rt)
          }
          aspect2 = FSMRuntimeAspect.transformZIO[Unit, TestState, TestEvent, Any] { rt =>
            order.update(_ :+ 2).as(rt)
          }
          combined = aspect1.andThen(aspect2)
          _      <- (testMachine.start(A) @@ combined)
          result <- order.get
        yield assertTrue(result == List(1, 2))
      },
      test("chaining multiple aspects with @@") {
        for
          order <- Ref.make(List.empty[Int])
          aspect1 = FSMRuntimeAspect.transformZIO[Unit, TestState, TestEvent, Any] { rt =>
            order.update(_ :+ 1).as(rt)
          }
          aspect2 = FSMRuntimeAspect.transformZIO[Unit, TestState, TestEvent, Any] { rt =>
            order.update(_ :+ 2).as(rt)
          }
          aspect3 = FSMRuntimeAspect.transformZIO[Unit, TestState, TestEvent, Any] { rt =>
            order.update(_ :+ 3).as(rt)
          }
          _      <- (testMachine.start(A) @@ aspect1 @@ aspect2 @@ aspect3)
          result <- order.get
        yield assertTrue(result == List(1, 2, 3))
      },
    ),
    suite("@@ extension method")(
      test("applies single aspect") {
        val aspect = FSMRuntimeAspect.identity[Unit, TestState, TestEvent]
        for
          runtime <- testMachine.start(A) @@ aspect
          state   <- runtime.currentState
        yield assertTrue(state == A)
      },
      test("applies multiple aspects left to right") {
        for
          calls <- Ref.make(0)
          aspect1 = FSMRuntimeAspect.transformZIO[Unit, TestState, TestEvent, Any] { rt =>
            calls.update(_ + 1).as(rt)
          }
          aspect2 = FSMRuntimeAspect.transformZIO[Unit, TestState, TestEvent, Any] { rt =>
            calls.update(_ + 10).as(rt)
          }
          _     <- testMachine.start(A) @@ aspect1 @@ aspect2
          total <- calls.get
        yield assertTrue(total == 11)
      },
    ),
  ).provideLayer(ZLayer.succeed(zio.Scope.global)) @@ TestAspect.timeout(10.seconds)

end FSMRuntimeAspectSpec
