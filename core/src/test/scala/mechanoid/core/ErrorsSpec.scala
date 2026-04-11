package mechanoid.core

import zio.test.*

object ErrorsSpec extends ZIOSpecDefault:

  def spec = suite("ErrorsSpec")(
    suite("InvalidTransitionError")(
      test("creates with default message") {
        val error = InvalidTransitionError("state", "event")
        assertTrue(
          error.currentState == "state",
          error.event == "event",
          error.message == "No transition defined",
        )
      },
      test("creates with custom message") {
        val error = InvalidTransitionError("state", "event", "custom message")
        assertTrue(error.message == "custom message")
      },
    ),
    suite("FSMStoppedError")(
      test("stores reason") {
        val error = FSMStoppedError(Some("reason"))
        assertTrue(error.reason == Some("reason"))
      },
      test("allows None reason") {
        val error = FSMStoppedError(None)
        assertTrue(error.reason.isEmpty)
      },
    ),
    suite("ActionFailedError")(
      test("wraps cause") {
        val cause = new RuntimeException("test")
        val error = ActionFailedError(cause)
        assertTrue(error.cause == cause)
      },
      test("can wrap string cause") {
        val error = ActionFailedError("error message")
        assertTrue(error.cause == "error message")
      },
    ),
    suite("ProcessingTimeoutError")(
      test("stores state and timeout") {
        val error = ProcessingTimeoutError("state", 5000L)
        assertTrue(error.currentState == "state", error.timeoutMs == 5000L)
      }
    ),
    suite("PersistenceError")(
      test("creates with message only") {
        val error = PersistenceError("message")
        assertTrue(
          error.message == "message",
          error.cause.isEmpty,
          error.getMessage == "message",
        )
      },
      test("creates with message and cause") {
        val cause = new RuntimeException("cause")
        val error = PersistenceError("message", Some(cause))
        assertTrue(
          error.message == "message",
          error.cause == Some(cause),
          error.getCause == cause,
        )
      },
      test("apply creates from Throwable") {
        val cause = new RuntimeException("test error")
        val error = PersistenceError(cause)
        assertTrue(
          error.message == "test error",
          error.cause == Some(cause),
        )
      },
      test("apply handles null message in Throwable") {
        val cause = new RuntimeException(null: String)
        val error = PersistenceError(cause)
        assertTrue(error.message == "RuntimeException")
      },
      test("fromError converts any error type") {
        val error = PersistenceError.fromError("string error")
        assertTrue(error.message == "string error")
      },
    ),
    suite("SequenceConflictError")(
      test("formats message correctly") {
        val error = SequenceConflictError("fsm-1", 5L, 3L)
        assertTrue(
          error.instanceId == "fsm-1",
          error.expectedSeqNr == 5L,
          error.actualSeqNr == 3L,
          error.getMessage.contains("fsm-1"),
          error.getMessage.contains("5"),
          error.getMessage.contains("3"),
        )
      }
    ),
    suite("EventReplayError")(
      test("formats message correctly") {
        val error = EventReplayError("StateA", "EventB", 10L)
        assertTrue(
          error.currentState == "StateA",
          error.event == "EventB",
          error.sequenceNr == 10L,
          error.getMessage.contains("EventB"),
          error.getMessage.contains("10"),
          error.getMessage.contains("StateA"),
        )
      }
    ),
    suite("LockingError")(
      test("wraps cause and provides message") {
        val cause = FSMStoppedError(Some("test"))
        val error = LockingError(cause)
        assertTrue(
          error.cause == cause,
          error.message.contains("Locking operation failed"),
        )
      }
    ),
  )

end ErrorsSpec
