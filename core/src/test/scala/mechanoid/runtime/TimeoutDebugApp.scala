package mechanoid.runtime

import zio.*
import mechanoid.*

object TimeoutDebugApp extends ZIOAppDefault:

  enum State derives Finite:
    case Idle, Waiting, TimedOut, Done

  enum Event derives Finite:
    case Start, Complete, Timeout

  import State.*
  import Event.*

  val machine = Machine(
    assembly[State, Event](
      (Idle via Start to Waiting) @@ Aspect.timeout(500.millis, Timeout),
      Waiting via Complete to Done,
      Waiting via Timeout to TimedOut,
    )
  )

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> Runtime.addLogger(
      ZLogger.default.map(println).filterLogLevel(_ >= LogLevel.Debug)
    )

  def run =
    ZIO.scoped {
      for
        _       <- Console.printLine("Creating runtime...")
        runtime <- machine.start(Idle)

        initialState <- runtime.currentState
        _            <- Console.printLine(s"Initial state: $initialState")
        _            <- Console.printLine(s"Timeout config for Waiting: ${runtime.timeoutConfigForState(Waiting)}")

        _      <- Console.printLine("Sending Start event...")
        _      <- runtime.send(Start)
        state1 <- runtime.currentState
        _      <- Console.printLine(s"State after Start: $state1")

        _ <- Console.printLine("Waiting 1 second for timeout to fire...")
        _ <- ZIO.sleep(1.second)

        state2 <- runtime.currentState
        _      <- Console.printLine(s"State after waiting: $state2")

        _ <- Console.printLine(
          if state2 == TimedOut then "SUCCESS: Timeout fired!" else "FAILURE: Timeout did NOT fire"
        )
      yield ()
    }
end TimeoutDebugApp
