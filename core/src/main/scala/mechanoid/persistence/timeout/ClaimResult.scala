package mechanoid.persistence.timeout

import java.time.Instant

/** Result of attempting to claim a timeout for processing.
  *
  * Claiming is an atomic operation that ensures only one sweeper node processes each timeout, even in distributed
  * deployments.
  *
  * ==Usage Pattern==
  *
  * {{{
  * store.claim(instanceId, name, nodeId, claimDuration, now).flatMap {
  *   case ClaimResult.Claimed(timeout) =>
  *     // Successfully claimed - fire the timeout
  *     fireTimeout(timeout) *> store.complete(instanceId, name, timeout.sequenceNr)
  *
  *   case ClaimResult.AlreadyClaimed(byNode, until) =>
  *     // Another node is processing - skip
  *     ZIO.logDebug(s"Timeout claimed by \$byNode until \$until")
  *
  *   case ClaimResult.NotFound =>
  *     // Timeout was cancelled or completed between query and claim
  *     ZIO.unit
  *
  *   case ClaimResult.StateChanged(currentState) =>
  *     // FSM state changed - timeout no longer valid
  *     store.complete(instanceId, name, timeout.sequenceNr)
  * }
  * }}}
  */
enum ClaimResult:
  /** Successfully claimed the timeout for processing.
    *
    * The sweeper should now fire the timeout event and then call `complete()` to remove it from the store.
    *
    * @param timeout
    *   The claimed timeout with updated claim fields
    */
  case Claimed[Id](timeout: ScheduledTimeout[Id])

  /** The timeout is already claimed by another node.
    *
    * The sweeper should skip this timeout - another node is handling it. If that node crashes, the claim will expire
    * and another sweeper can retry.
    *
    * @param byNode
    *   The node ID that holds the claim
    * @param until
    *   When the claim expires
    */
  case AlreadyClaimed(byNode: String, until: Instant)

  /** The timeout was not found in the store.
    *
    * This typically means:
    *   - The timeout was cancelled (FSM exited the state)
    *   - The timeout was already completed by another node
    *   - Race condition between query and claim
    */
  case NotFound

  /** The FSM state has changed since the timeout was scheduled.
    *
    * This occurs when the sweeper verifies the FSM is still in the expected state before firing. The timeout should be
    * removed without firing.
    *
    * @param currentState
    *   The FSM's current state (different from timeout's state)
    */
  case StateChanged(currentState: String)
end ClaimResult
