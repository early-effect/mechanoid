package mechanoid.core

/** The outcome of processing an event in the FSM.
  *
  * @tparam S
  *   The state type
  */
enum TransitionResult[+S]:
  /** Remain in the current leaf. `state` is the instance to keep (identity or rewritten payload).
    *
    * Stay does not run state exit/entry and does not push history. Non-timeout events leave armed timeouts alone. A
    * named timeout event that Stays completes and re-arms only that name.
    */
  case Stay(state: S)

  /** Leave the current leaf (or re-enter it). Runs exit/entry and timeout restart.
    *
    * @param state
    *   The target state to transition to
    */
  case Goto(state: S)

  /** Stop the FSM.
    *
    * @param reason
    *   Optional reason for stopping
    */
  case Stop(reason: Option[String] = None)
end TransitionResult

object TransitionResult:
  /** Convenience method to create a Stop result with a reason. */
  def stop[S](reason: String): TransitionResult[S] =
    Stop(Some(reason))

  /** Convenience method to create a Stop result without a reason. */
  def stop[S]: TransitionResult[S] =
    Stop(None)
