package mechanoid

import mechanoid.core.*

object Mechanoid

// Re-export core types
export core.Finite
export core.TransitionResult
export core.TransitionOutcome
export core.Transition
export core.StateTimeout
export core.FSMState
export core.MechanoidError
export core.InvalidTransitionError
export core.FSMStoppedError
export core.ActionFailedError
export core.ProcessingTimeoutError
export core.CaseHasher

// Re-export suite-style DSL
export machine.Machine
export machine.Assembly
export machine.assembly
export machine.assemblyAll
export machine.combine
export machine.++
export machine.all
export machine.anyOf
export machine.via
export machine.Aspect
export machine.stay
export machine.stop
export machine.TransitionSpec as MachineTransitionSpec
export machine.ViaBuilder
export machine.AllMatcher
export machine.AnyOfMatcher
export machine.AnyOfEventMatcher
export machine.event
export machine.anyOfEvents
export machine.EventMatcher

// Re-export runtime
export runtime.FSMRuntime
export runtime.FSMRuntimeAspect
export runtime.`@@` // Extension method for aspect composition

// Re-export locking strategies
export runtime.locking.LockingStrategy
export runtime.locking.OptimisticLockingStrategy
export runtime.locking.DistributedLockingStrategy

// Re-export timeout strategies
export runtime.timeout.TimeoutStrategy
export runtime.timeout.FiberTimeoutStrategy
export runtime.timeout.DurableTimeoutStrategy

// Re-export persistence
export persistence.StoredEvent
export persistence.FSMSnapshot
export persistence.EventStore
export core.PersistenceError
export core.SequenceConflictError
export core.EventReplayError

// Re-export visualization
export visualization.TransitionKind
export visualization.TransitionMeta
export visualization.StateMeta
export visualization.FSMMeta
export visualization.TraceStep
export visualization.ExecutionTrace
export visualization.MermaidVisualizer
export visualization.GraphVizVisualizer

// Re-export redaction
export core.sensitive
export core.Redactor
export core.Redactor.redacted
export core.Redactor.redactedPretty

// Re-export in-memory stores
export stores.InMemoryEventStore
export stores.InMemoryTimeoutStore
export stores.InMemoryFSMInstanceLock

// Re-export locking types
export persistence.lock.FSMInstanceLock
export persistence.lock.LockedFSMRuntime
export persistence.lock.LockConfig
export persistence.lock.LockToken
export persistence.lock.LockResult
export persistence.lock.LockError
export persistence.lock.LockHeartbeatConfig
export persistence.lock.LockLostBehavior
export persistence.lock.AtomicTransactionContext
export core.LockingError

// Re-export timeout persistence types
export persistence.timeout.TimeoutStore
export persistence.timeout.TimeoutSweeper
export persistence.timeout.TimeoutSweeperConfig
export persistence.timeout.ScheduledTimeout
export persistence.timeout.LeaderElectionConfig
export persistence.timeout.LeaseStore
export persistence.timeout.SweeperMetrics
export persistence.timeout.ClaimResult

// Re-export visualization extension methods
export visualization.toMermaidStateDiagram
export visualization.toMermaidFlowchart
export visualization.toMermaidFlowchartWithTrace
export visualization.toGraphViz
export visualization.toGraphVizWithTrace
export visualization.toMermaidSequenceDiagram
export visualization.toGraphVizTimeline

// Extension methods for DSL - re-exported from machine package
extension [S](inline state: S)(using scala.util.NotGiven[S <:< machine.IsMatcher])
  /** Handle anyOf events: `State viaAnyOf anyOfEvents(E1, E2) to Target` */
  inline infix def viaAnyOf[E](events: AnyOfEventMatcher[E]): ViaBuilder[S, E] =
    val stateHash = machine.Macros.computeHashFor(state)
    new ViaBuilder[S, E](Set(stateHash), events.hashes, List(state.toString), events.names)

  /** Handle all events: `State viaAll all[E] to Target` */
  inline infix def viaAll[E](events: AllMatcher[E]): ViaBuilder[S, E] =
    val stateHash = machine.Macros.computeHashFor(state)
    new ViaBuilder[S, E](Set(stateHash), events.hashes, List(state.toString), events.names)
end extension
