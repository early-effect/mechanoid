package mechanoid.core

import zio.test.*

object TransitionResultSpec extends ZIOSpecDefault:

  def spec = suite("TransitionResult")(
    test("Stay is a singleton") {
      val result: TransitionResult[String] = TransitionResult.Stay
      assertTrue(result == TransitionResult.Stay)
    },
    test("Goto wraps the target state") {
      val result = TransitionResult.Goto("active")
      assertTrue(result == TransitionResult.Goto("active"))
    },
    test("Stop with no reason defaults to None") {
      val result = TransitionResult.Stop()
      assertTrue(result == TransitionResult.Stop(None))
    },
    test("stop convenience method with reason") {
      val result = TransitionResult.stop[String]("shutdown")
      result match
        case TransitionResult.Stop(Some(r)) => assertTrue(r == "shutdown")
        case _                              => assertTrue(false)
    },
    test("stop convenience method without reason") {
      val result = TransitionResult.stop[String]
      result match
        case TransitionResult.Stop(None) => assertTrue(true)
        case _                           => assertTrue(false)
    },
  )
end TransitionResultSpec
