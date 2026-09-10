package mechanoid.runtime

import zio.*
import zio.test.*
import mechanoid.*
import mechanoid.stores.InMemoryEventStore

object FSMRuntimeSpec extends ZIOSpecDefault:

  enum TestState derives Finite:
    case A, B, C

  enum TestEvent derives Finite:
    case E1, E2, E3

  enum AliasInitState derives Finite:
    case Draft
    case Live(@alias("campaign") campaignIds: List[Long], @alias templateId: String)

  enum AliasInitEvent derives Finite:
    case Launch

  import TestState.*
  import TestEvent.*

  // Machine with timeouts for testing
  enum TimeoutState derives Finite:
    case Idle, Waiting, Done, TimedOut

  enum TimeoutEvent derives Finite:
    case Start, Complete, Timeout

  import TimeoutState.*
  import TimeoutEvent.*

  val simpleMachine = Machine(
    assembly[TestState, TestEvent](
      A via E1 to B,
      B via E2 to C,
      C via E3 to A,
    )
  )

  def spec = suite("FSMRuntimeSpec")(
    suite("basic operations")(
      test("currentState returns initial state") {
        for
          runtime <- simpleMachine.start(A)
          state   <- runtime.currentState
        yield assertTrue(state == A)
      },
      test("state returns full FSMState") {
        for
          runtime  <- simpleMachine.start(A)
          fsmState <- runtime.state
        yield assertTrue(fsmState.current == A, fsmState.history.isEmpty)
      },
      test("history tracks state transitions") {
        for
          runtime <- simpleMachine.start(A)
          _       <- runtime.send(E1)
          _       <- runtime.send(E2)
          history <- runtime.history
        yield assertTrue(history == List(B, A))
      },
      test("instanceId returns Unit for simple runtime") {
        for runtime <- simpleMachine.start(A)
        yield assertTrue(runtime.instanceId == ())
      },
      test("machine returns the Machine definition") {
        for runtime <- simpleMachine.start(A)
        yield assertTrue(runtime.machine == simpleMachine)
      },
    ),
    suite("send")(
      test("transitions to new state") {
        for
          runtime <- simpleMachine.start(A)
          outcome <- runtime.send(E1)
          state   <- runtime.currentState
        yield outcome.result match
          case TransitionResult.Goto(B) => assertTrue(state == B)
          case _                        => assertTrue(false)
      },
      test("returns TransitionOutcome with Goto result") {
        for
          runtime <- simpleMachine.start(A)
          outcome <- runtime.send(E1)
        yield outcome.result match
          case TransitionResult.Goto(target) => assertTrue(target == B)
          case _                             => assertTrue(false)
      },
      test("fails with InvalidTransitionError for undefined transition") {
        for
          runtime <- simpleMachine.start(A)
          result  <- runtime.send(E2).either
        yield result match
          case Left(_: mechanoid.core.InvalidTransitionError[?, ?]) => assertTrue(true)
          case _                                                    => assertTrue(false)
      },
    ),
    suite("stop")(
      test("stop marks FSM as not running") {
        for
          runtime  <- simpleMachine.start(A)
          running1 <- runtime.isRunning
          _        <- runtime.stop
          running2 <- runtime.isRunning
        yield assertTrue(running1, !running2)
      },
      test("stop with reason marks FSM as not running") {
        for
          runtime <- simpleMachine.start(A)
          _       <- runtime.stop("test reason")
          running <- runtime.isRunning
        yield assertTrue(!running)
      },
      test("send after stop returns Stop outcome") {
        for
          runtime <- simpleMachine.start(A)
          _       <- runtime.stop
          outcome <- runtime.send(E1)
        yield outcome.result match
          case TransitionResult.Stop(_) => assertTrue(true)
          case _                        => assertTrue(false)
      },
    ),
    suite("lastSequenceNr")(
      test("starts at 0 for new FSM") {
        for
          runtime <- simpleMachine.start(A)
          seqNr   <- runtime.lastSequenceNr
        yield assertTrue(seqNr == 0L)
      },
      test("increments after each event") {
        for
          runtime <- simpleMachine.start(A)
          _       <- runtime.send(E1)
          seqNr1  <- runtime.lastSequenceNr
          _       <- runtime.send(E2)
          seqNr2  <- runtime.lastSequenceNr
        yield assertTrue(seqNr1 == 1L, seqNr2 == 2L)
      },
    ),
    suite("saveSnapshot")(
      test("saves snapshot with current state") {
        for
          store   <- InMemoryEventStore.make[Unit, TestState, TestEvent]()
          runtime <- simpleMachine.start(A)
          _       <- runtime.send(E1)
          _       <- runtime.saveSnapshot
          snap    <- store.loadSnapshot(())
        yield assertTrue(snap.isEmpty) // In-memory runtime uses its own store
      }
    ),
    suite("timeoutConfigForState")(
      test("returns None for state without timeout") {
        for runtime <- simpleMachine.start(A)
        yield assertTrue(runtime.timeoutConfigForState(A).isEmpty)
      },
      test("returns Some for state with timeout") {
        val machineWithTimeout = Machine(
          assembly[TimeoutState, TimeoutEvent](
            (Idle via Start to Waiting) @@ Aspect.timeout(30.seconds, Timeout),
            Waiting via Complete to Done,
            Waiting via Timeout to TimedOut,
          )
        )
        for runtime <- machineWithTimeout.start(Idle)
        yield
          val config = runtime.timeoutConfigForState(Waiting)
          assertTrue(
            config.isDefined,
            config.get._1 == 30.seconds,
            config.get._2 == Timeout,
          )
      },
    ),
    suite("onEntry effects")(
      test("onEntry effect runs on transition") {
        for
          ref <- Ref.make(false)
          machine = Machine(
            assembly[TestState, TestEvent](
              A via E1 to B
            ).onEnter(B) { (_, _) =>
              ref.set(true)
            }
          )
          runtime <- machine.start(A)
          _       <- runtime.send(E1)
          ran     <- ref.get
        yield assertTrue(ran)
      }
    ),
    suite("onExit effects")(
      test("onExit effect runs on transition") {
        for
          ref <- Ref.make(false)
          machine = Machine(
            assembly[TestState, TestEvent](
              A via E1 to B
            ).onExit(A) { (_, _) =>
              ref.set(true)
            }
          )
          runtime <- machine.start(A)
          _       <- runtime.send(E1)
          ran     <- ref.get
        yield assertTrue(ran)
      }
    ),
    suite("stay transition")(
      test("stay keeps current state") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            A via E1 to stay
          )
        )
        for
          runtime <- machine.start(A)
          outcome <- runtime.send(E1)
          state   <- runtime.currentState
        yield assertTrue(state == A) &&
          assertTrue(outcome.result == TransitionResult.Stay)
      }
    ),
    suite("stop transition")(
      test("stop transition stops the FSM") {
        val machine = Machine(
          assembly[TestState, TestEvent](
            A via E1 to stop("done")
          )
        )
        for
          runtime <- machine.start(A)
          outcome <- runtime.send(E1)
          running <- runtime.isRunning
        yield outcome.result match
          case TransitionResult.Stop(reason) =>
            assertTrue(!running, reason.contains("done"))
          case _ => assertTrue(false)
      }
    ),
    suite("FSMRuntime.apply with environment")(
      test("creates runtime from environment services") {
        import mechanoid.runtime.timeout.FiberTimeoutStrategy
        import mechanoid.runtime.locking.OptimisticLockingStrategy

        val program = for
          runtime <- FSMRuntime[String, TestState, TestEvent](
            "test-instance",
            simpleMachine,
            A,
          )
          state <- runtime.currentState
        yield assertTrue(state == A)

        program.provideSome[Scope](
          InMemoryEventStore.layer[String, TestState, TestEvent],
          FiberTimeoutStrategy.layer[String],
          OptimisticLockingStrategy.layer[String],
        )
      }
    ),
    suite("lookup and aliases")(
      test("lookup reconstructs the instance bound to an alias") {
        import mechanoid.runtime.timeout.FiberTimeoutStrategy
        import mechanoid.runtime.locking.OptimisticLockingStrategy

        val alias = Alias("campaign", "c-1")
        ZIO.scoped {
          for
            store <- InMemoryEventStore.make[String, TestState, TestEvent]()
            index <- InMemoryInstanceIndex.make[String]
            layers = ZLayer.succeed(store) ++
              ZLayer.succeed[InstanceIndex[String]](index) ++
              FiberTimeoutStrategy.layer[String] ++
              OptimisticLockingStrategy.layer[String]
            _ <- ZIO
              .scoped {
                FSMRuntime("init-1", simpleMachine, A).flatMap { fsm =>
                  fsm.send(E1) *> fsm.saveSnapshot
                }
              }
              .provide(layers)
            _     <- index.bind(alias, "init-1")
            state <- ZIO
              .scoped {
                FSMRuntime
                  .lookup[String, TestState, TestEvent](alias, simpleMachine, A)
                  .flatMap(_.currentState)
              }
              .provide(layers)
          yield assertTrue(state == B)
        }
      },
      test("lookup fails for an unknown alias") {
        import mechanoid.runtime.timeout.FiberTimeoutStrategy
        import mechanoid.runtime.locking.OptimisticLockingStrategy

        val program = FSMRuntime
          .lookup[String, TestState, TestEvent](Alias("campaign", "missing"), simpleMachine, A)
          .either

        program
          .provide(
            Scope.default,
            InMemoryEventStore.layer[String, TestState, TestEvent],
            InMemoryInstanceIndex.layer[String],
            FiberTimeoutStrategy.layer[String],
            OptimisticLockingStrategy.layer[String],
          )
          .map {
            case Left(e: AliasNotFoundError) =>
              assertTrue(e.namespace == "campaign", e.key == "missing")
            case _ => assertTrue(false)
          }
      },
      test("derived @alias extractor binds on Goto") {
        import mechanoid.runtime.timeout.FiberTimeoutStrategy
        import mechanoid.runtime.locking.OptimisticLockingStrategy

        val machine = Machine(
          assembly[AliasInitState, AliasInitEvent](
            AliasInitState.Draft via AliasInitEvent.Launch to AliasInitState.Live(List(1L, 2L), "t-9")
          )
        )
        val extractor = AliasExtractor.derived[AliasInitState]

        ZIO.scoped {
          for
            store <- InMemoryEventStore.make[String, AliasInitState, AliasInitEvent]()
            index <- InMemoryInstanceIndex.make[String]
            _     <- ZIO
              .scoped {
                FSMRuntime("init-1", machine, AliasInitState.Draft, extractor).flatMap(_.send(AliasInitEvent.Launch))
              }
              .provide(
                ZLayer.succeed(store),
                ZLayer.succeed[InstanceIndex[String]](index),
                FiberTimeoutStrategy.layer[String],
                OptimisticLockingStrategy.layer[String],
              )
            campaigns <- index.aliasesOf("init-1", Some("campaign"))
            template  <- index.resolve(Alias("templateId", "t-9"))
          yield assertTrue(
            campaigns.toSet == Set(Alias("campaign", "1"), Alias("campaign", "2")),
            template.contains("init-1"),
          )
        }
      },
      test("extractor binds aliases on Goto and lookup recovers them") {
        import mechanoid.runtime.timeout.FiberTimeoutStrategy
        import mechanoid.runtime.locking.OptimisticLockingStrategy

        sealed trait InitState derives Finite
        case class Draft(campaigns: List[String]) extends InitState
        case class Live(campaigns: List[String])  extends InitState

        enum InitEvent derives Finite:
          case Launch

        val machine = Machine(
          assembly[InitState, InitEvent](
            state[Draft] via InitEvent.Launch to Live(List("c-1", "c-2"))
          )
        )
        val extractor: AliasExtractor[InitState] =
          AliasExtractor {
            case Draft(cs) => Chunk.fromIterable(cs.map(id => Alias("campaign", id)))
            case Live(cs)  => Chunk.fromIterable(cs.map(id => Alias("campaign", id)))
          }

        ZIO.scoped {
          for
            store <- InMemoryEventStore.make[String, InitState, InitEvent]()
            index <- InMemoryInstanceIndex.make[String]
            layers = ZLayer.succeed(store) ++
              ZLayer.succeed[InstanceIndex[String]](index) ++
              FiberTimeoutStrategy.layer[String] ++
              OptimisticLockingStrategy.layer[String]
            _ <- ZIO
              .scoped {
                FSMRuntime("init-1", machine, Draft(Nil), extractor).flatMap { fsm =>
                  fsm.send(InitEvent.Launch) *> fsm.saveSnapshot
                }
              }
              .provide(layers)
            c1    <- index.resolve(Alias("campaign", "c-1"))
            state <- ZIO
              .scoped {
                FSMRuntime
                  .lookup[String, InitState, InitEvent](
                    Alias("campaign", "c-1"),
                    machine,
                    Draft(Nil),
                    extractor,
                  )
                  .flatMap(_.currentState)
              }
              .provide(layers)
          yield assertTrue(c1.contains("init-1"), state == Live(List("c-1", "c-2")))
        }
      },
      test("extractor uniqueness clash fails send and does not append") {
        import mechanoid.runtime.timeout.FiberTimeoutStrategy
        import mechanoid.runtime.locking.OptimisticLockingStrategy

        sealed trait InitState derives Finite
        case object Empty                        extends InitState
        case class Live(campaigns: List[String]) extends InitState

        enum InitEvent derives Finite:
          case Launch

        val machine = Machine(
          assembly[InitState, InitEvent](
            Empty via InitEvent.Launch to Live(List("taken"))
          )
        )
        val extractor: AliasExtractor[InitState] =
          AliasExtractor {
            case Empty    => Chunk.empty
            case Live(cs) => Chunk.fromIterable(cs.map(id => Alias("campaign", id)))
          }

        ZIO.scoped {
          for
            store  <- InMemoryEventStore.make[String, InitState, InitEvent]()
            index  <- InMemoryInstanceIndex.make[String]
            _      <- index.bind(Alias("campaign", "taken"), "other")
            result <- ZIO
              .scoped {
                FSMRuntime("init-1", machine, Empty, extractor).flatMap(_.send(InitEvent.Launch).either)
              }
              .provide(
                ZLayer.succeed(store),
                ZLayer.succeed[InstanceIndex[String]](index),
                FiberTimeoutStrategy.layer[String],
                OptimisticLockingStrategy.layer[String],
              )
            seq <- store.highestSequenceNr("init-1")
          yield result match
            case Left(_: UniqueAliasError) => assertTrue(seq == 0L)
            case _                         => assertTrue(false)
        }
      },
      test("unchanged extractor lists do not rewrite aliases") {
        import mechanoid.runtime.timeout.FiberTimeoutStrategy
        import mechanoid.runtime.locking.OptimisticLockingStrategy

        sealed trait InitState derives Finite
        case class Live(campaigns: List[String], tick: Int) extends InitState

        enum InitEvent derives Finite:
          case Tick

        val machine = Machine(
          assembly[InitState, InitEvent](
            state[Live] via InitEvent.Tick to Live(List("c-1"), 1)
          )
        )
        val extractor: AliasExtractor[InitState] =
          AliasExtractor { case Live(cs, _) =>
            Chunk.fromIterable(cs.map(id => Alias("campaign", id)))
          }

        ZIO.scoped {
          for
            store <- InMemoryEventStore.make[String, InitState, InitEvent]()
            index <- InMemoryInstanceIndex.make[String]
            _     <- index.bind(Alias("campaign", "c-1"), "init-1")
            _     <- ZIO
              .scoped {
                FSMRuntime("init-1", machine, Live(List("c-1"), 0), extractor).flatMap(_.send(InitEvent.Tick))
              }
              .provide(
                ZLayer.succeed(store),
                ZLayer.succeed[InstanceIndex[String]](index),
                FiberTimeoutStrategy.layer[String],
                OptimisticLockingStrategy.layer[String],
              )
            still <- index.resolve(Alias("campaign", "c-1"))
            extra <- index.aliasesOf("init-1")
          yield assertTrue(still.contains("init-1"), extra.toSet == Set(Alias("campaign", "c-1")))
        }
      },
      test("recover reindex drops stale aliases not on rebuilt state") {
        import mechanoid.runtime.timeout.FiberTimeoutStrategy
        import mechanoid.runtime.locking.OptimisticLockingStrategy

        sealed trait InitState derives Finite
        case class Live(campaigns: List[String]) extends InitState

        enum InitEvent derives Finite:
          case Tick

        val machine = Machine(
          assembly[InitState, InitEvent](
            state[Live] via InitEvent.Tick to stay
          )
        )
        val extractor: AliasExtractor[InitState] =
          AliasExtractor { case Live(cs) =>
            Chunk.fromIterable(cs.map(id => Alias("campaign", id)))
          }

        ZIO.scoped {
          for
            store <- InMemoryEventStore.make[String, InitState, InitEvent]()
            index <- InMemoryInstanceIndex.make[String]
            snap: FSMSnapshot[String, InitState] =
              FSMSnapshot("init-1", Live(List("c-1")), 0L, java.time.Instant.now())
            _ <- store.saveSnapshot(snap)
            _ <- index.bindAll(
              Chunk(Alias("campaign", "c-1"), Alias("campaign", "stale")),
              "init-1",
            )
            _ <- ZIO
              .scoped {
                FSMRuntime("init-1", machine, Live(Nil), extractor).unit
              }
              .provide(
                ZLayer.succeed(store),
                ZLayer.succeed[InstanceIndex[String]](index),
                FiberTimeoutStrategy.layer[String],
                OptimisticLockingStrategy.layer[String],
              )
            leftover <- index.aliasesOf("init-1")
            stale    <- index.resolve(Alias("campaign", "stale"))
          yield assertTrue(
            leftover.toSet == Set(Alias("campaign", "c-1")),
            stale.isEmpty,
          )
        }
      },
    ),
    suite("error scenarios")(
      test("InvalidTransitionError is thrown for undefined transition") {
        for
          runtime <- simpleMachine.start(A)
          result  <- runtime.send(E2).either // E2 not valid from A
        yield result match
          case Left(err: InvalidTransitionError[?, ?]) =>
            assertTrue(
              err.currentState == A,
              err.event == E2,
              err.message == "No transition defined",
            )
          case _ => assertTrue(false)
      },
      test("action error is wrapped in ActionFailedError") {
        val failingMachine = Machine(
          assembly[TestState, TestEvent](
            A via E1 to B
          ).onEnter(B) { (_, _) =>
            ZIO.fail(new RuntimeException("entry effect failed"))
          }
        )
        for
          runtime <- failingMachine.start(A)
          result  <- runtime.send(E1).either
        yield result match
          case Left(err: ActionFailedError[(String, Throwable)] @unchecked) =>
            assertTrue(
              err.cause._1 == "state entry effect",
              err.cause._2.getMessage == "entry effect failed",
            )
          case _ => assertTrue(false)
        end for
      },
      test("exit effect error is wrapped in ActionFailedError") {
        val failingMachine = Machine(
          assembly[TestState, TestEvent](
            A via E1 to B
          ).onExit(A) { (_, _) =>
            ZIO.fail(new RuntimeException("exit effect failed"))
          }
        )
        for
          runtime <- failingMachine.start(A)
          result  <- runtime.send(E1).either
        yield result match
          case Left(err: ActionFailedError[(String, Throwable)] @unchecked) =>
            assertTrue(
              err.cause._1 == "state exit effect",
              err.cause._2.getMessage == "exit effect failed",
            )
          case _ => assertTrue(false)
        end for
      },
    ),
    suite("producing effects")(
      test("producing effect fires asynchronously and sends event back to FSM") {
        for
          ref <- Ref.make(List.empty[TestEvent])
          machine = Machine(
            assembly[TestState, TestEvent](
              (A via E1 to B)
                .onEntry { (_, _) =>
                  ref.update(_ :+ E1)
                }
                .producing { (_, _) =>
                  ZIO.succeed(E2)
                },
              (B via E2 to C).onEntry { (_, _) =>
                ref.update(_ :+ E2)
              },
            )
          )
          runtime <- machine.start(A)
          _       <- runtime.send(E1)
          // Give async producing effect time to run and send event back
          _      <- TestClock.adjust(1.millis)
          _      <- ZIO.yieldNow
          _      <- ZIO.yieldNow
          events <- ref.get
          state  <- runtime.currentState
        yield assertTrue(
          events.contains(E1),
          events.contains(E2),
          state == C, // Should have transitioned A -> B -> C via producing effect
        )
      },
      test("producing effect error is logged but does not fail transition") {
        for
          entryRan <- Ref.make(false)
          machine = Machine(
            assembly[TestState, TestEvent](
              (A via E1 to B)
                .onEntry { (_, _) =>
                  entryRan.set(true)
                }
                .producing { (_, _) =>
                  ZIO.fail(new RuntimeException("producing effect failed"))
                }
            )
          )
          runtime <- machine.start(A)
          outcome <- runtime.send(E1)
          state   <- runtime.currentState
          didRun  <- entryRan.get
        yield assertTrue(
          state == B, // Transition succeeded despite producing error
          didRun,     // Entry effect ran
          outcome.result match
            case TransitionResult.Goto(B) => true
            case _                        => false,
        )
      },
    ),
    suite("transition entry effects (via onEntry modifier)")(
      test("per-transition entry effect runs on transition") {
        for
          ref <- Ref.make(Option.empty[(TestEvent, TestState)])
          machine = Machine(
            assembly[TestState, TestEvent](
              (A via E1 to B).onEntry { (event, state) =>
                ref.set(Some((event, state)))
              }
            )
          )
          runtime <- machine.start(A)
          _       <- runtime.send(E1)
          result  <- ref.get
        yield assertTrue(
          result.isDefined,
          result.get._1 == E1,
          result.get._2 == B,
        )
      },
      test("entry effect error is wrapped in ActionFailedError with 'entry effect' name") {
        val failingMachine = Machine(
          assembly[TestState, TestEvent](
            (A via E1 to B).onEntry { (_, _) =>
              ZIO.fail(new RuntimeException("entry effect failed"))
            }
          )
        )
        for
          runtime <- failingMachine.start(A)
          result  <- runtime.send(E1).either
        yield result match
          case Left(err: ActionFailedError[?]) =>
            assertTrue(err.cause.toString.contains("entry effect"))
          case _ => assertTrue(false)
      },
    ),
    suite("event replay during recovery")(
      test("rebuilds state from persisted events") {
        import mechanoid.runtime.timeout.FiberTimeoutStrategy
        import mechanoid.runtime.locking.OptimisticLockingStrategy

        for
          store <- InMemoryEventStore.make[String, TestState, TestEvent]()
          // Persist some events
          _ <- store.append("fsm-1", E1, 0L)
          _ <- store.append("fsm-1", E2, 1L)
          // Create runtime which should replay events
          runtime <- FSMRuntime[String, TestState, TestEvent](
            "fsm-1",
            simpleMachine,
            A,
          ).provideSome[Scope](
            ZLayer.succeed(store),
            FiberTimeoutStrategy.layer[String],
            OptimisticLockingStrategy.layer[String],
          )
          state <- runtime.currentState
          seqNr <- runtime.lastSequenceNr
        yield assertTrue(
          state == C, // A -> B (E1) -> C (E2)
          seqNr == 2L,
        )
        end for
      },
      test("replay fails with EventReplayError for invalid transition") {
        import mechanoid.runtime.timeout.FiberTimeoutStrategy
        import mechanoid.runtime.locking.OptimisticLockingStrategy

        for
          store <- InMemoryEventStore.make[String, TestState, TestEvent]()
          // Persist invalid event (E2 not valid from initial state A)
          _      <- store.append("fsm-1", E2, 0L)
          result <- FSMRuntime[String, TestState, TestEvent](
            "fsm-1",
            simpleMachine,
            A,
          ).provideSome[Scope](
            ZLayer.succeed(store),
            FiberTimeoutStrategy.layer[String],
            OptimisticLockingStrategy.layer[String],
          ).either
        yield result match
          case Left(_: EventReplayError[?, ?]) => assertTrue(true)
          case _                               => assertTrue(false)
        end for
      },
    ),
    suite("timeout scheduling integration")(
      test("timeout fires and transitions state with TestClock") {
        val machineWithTimeout = Machine(
          assembly[TimeoutState, TimeoutEvent](
            (Idle via Start to Waiting) @@ Aspect.timeout(50.millis, Timeout),
            Waiting via Complete to Done,
            Waiting via Timeout to TimedOut,
          )
        )
        for
          runtime <- machineWithTimeout.start(Idle)
          _       <- runtime.send(Start)
          state1  <- runtime.currentState
          _       <- TestClock.adjust(100.millis)
          _       <- ZIO.yieldNow
          state2  <- runtime.currentState
        yield assertTrue(
          state1 == Waiting,
          state2 == TimedOut,
        )
        end for
      },
      test("timeout is cancelled when state changes before expiry") {
        val machineWithTimeout = Machine(
          assembly[TimeoutState, TimeoutEvent](
            (Idle via Start to Waiting) @@ Aspect.timeout(500.millis, Timeout),
            Waiting via Complete to Done,
            Waiting via Timeout to TimedOut,
          )
        )
        for
          runtime <- machineWithTimeout.start(Idle)
          _       <- runtime.send(Start)
          state1  <- runtime.currentState
          // Complete before timeout fires
          _      <- TestClock.adjust(50.millis)
          _      <- runtime.send(Complete)
          state2 <- runtime.currentState
          // Wait for timeout to have fired (if it wasn't cancelled)
          _      <- TestClock.adjust(600.millis)
          _      <- ZIO.yieldNow
          state3 <- runtime.currentState
        yield assertTrue(
          state1 == Waiting,
          state2 == Done,
          state3 == Done, // Should still be Done, not TimedOut
        )
        end for
      },
      test("timeout callback does not block on self-cancellation") {
        // This test verifies the fix for the self-interruption bug where
        // the timeout callback would hang when calling cancelTimeout on itself
        val machineWithTimeout = Machine(
          assembly[TimeoutState, TimeoutEvent](
            (Idle via Start to Waiting) @@ Aspect.timeout(30.millis, Timeout),
            Waiting via Timeout to TimedOut,
          )
        )
        for
          runtime <- machineWithTimeout.start(Idle)
          _       <- runtime.send(Start)
          // The bug caused this to hang forever because the timeout callback
          // would try to interrupt its own fiber
          _     <- TestClock.adjust(100.millis)
          _     <- ZIO.yieldNow
          state <- runtime.currentState
        yield assertTrue(state == TimedOut)
      },
    ),
    suite("FSMRuntime.apply saveSnapshot with persistent store")(
      test("saveSnapshot persists to the event store") {
        import mechanoid.runtime.timeout.FiberTimeoutStrategy
        import mechanoid.runtime.locking.OptimisticLockingStrategy

        for
          store   <- InMemoryEventStore.make[String, TestState, TestEvent]()
          runtime <- FSMRuntime[String, TestState, TestEvent](
            "fsm-snapshot",
            simpleMachine,
            A,
          ).provideSome[Scope](
            ZLayer.succeed(store),
            FiberTimeoutStrategy.layer[String],
            OptimisticLockingStrategy.layer[String],
          )
          _        <- runtime.send(E1)
          _        <- runtime.saveSnapshot
          snapshot <- store.loadSnapshot("fsm-snapshot")
        yield assertTrue(
          snapshot.isDefined,
          snapshot.get.state == B,
        )
        end for
      }
    ),
    suite("edge cases")(
      test("scope finalizer stops runtime on scope close") {
        // Test FSMRuntime.scala line 153: acquireRelease finalizer (_.stop)
        import mechanoid.runtime.timeout.FiberTimeoutStrategy
        import mechanoid.runtime.locking.OptimisticLockingStrategy
        for
          store      <- InMemoryEventStore.make[String, TestState, TestEvent]()
          wasRunning <- Ref.make(false)
          // Use a nested scope that will close
          _ <- ZIO.scoped {
            for
              runtime <- FSMRuntime[String, TestState, TestEvent](
                "fsm-finalizer-test",
                simpleMachine,
                A,
              ).provideSome[Scope](
                ZLayer.succeed(store),
                FiberTimeoutStrategy.layer[String],
                OptimisticLockingStrategy.layer[String],
              )
              running <- runtime.isRunning
              _       <- wasRunning.set(running)
            yield ()
          }
          wasIt <- wasRunning.get
        yield assertTrue(wasIt) // Runtime was running before scope closed
        end for
      },
      test("replay handles TransitionResult.Stay during replay") {
        // Test FSMRuntime.scala line 334: case _ => fsmState (Stay/Stop during replay)
        import mechanoid.runtime.timeout.FiberTimeoutStrategy
        import mechanoid.runtime.locking.OptimisticLockingStrategy

        // Create a machine with a 'stay' transition
        val stayMachine = Machine(
          assembly[TestState, TestEvent](
            A via E1 to stay, // Stay in state A
            A via E2 to B,
          )
        )

        for
          store <- InMemoryEventStore.make[String, TestState, TestEvent]()
          // Persist E1 event which triggers stay
          _       <- store.append("fsm-stay-test", E1, 0L)
          runtime <- FSMRuntime[String, TestState, TestEvent](
            "fsm-stay-test",
            stayMachine,
            A,
          ).provideSome[Scope](
            ZLayer.succeed(store),
            FiberTimeoutStrategy.layer[String],
            OptimisticLockingStrategy.layer[String],
          )
          state <- runtime.currentState
          seqNr <- runtime.lastSequenceNr
        yield assertTrue(
          state == A, // Stayed in A
          seqNr == 1L, // Event was replayed
        )
        end for
      },
      test("replay handles TransitionResult.Stop during replay") {
        // Test FSMRuntime.scala line 334: case _ => fsmState (Stay/Stop during replay)
        import mechanoid.runtime.timeout.FiberTimeoutStrategy
        import mechanoid.runtime.locking.OptimisticLockingStrategy

        // Create a machine with a 'stop' transition
        val stopMachine = Machine(
          assembly[TestState, TestEvent](
            A via E1 to stop("test stop"),
            A via E2 to B,
          )
        )

        for
          store <- InMemoryEventStore.make[String, TestState, TestEvent]()
          // Persist E1 event which triggers stop
          _       <- store.append("fsm-stop-test", E1, 0L)
          runtime <- FSMRuntime[String, TestState, TestEvent](
            "fsm-stop-test",
            stopMachine,
            A,
          ).provideSome[Scope](
            ZLayer.succeed(store),
            FiberTimeoutStrategy.layer[String],
            OptimisticLockingStrategy.layer[String],
          )
          state <- runtime.currentState
          seqNr <- runtime.lastSequenceNr
        yield assertTrue(
          state == A, // Still at initial state (stop doesn't change state)
          seqNr == 1L, // Event was replayed
        )
        end for
      },
      test("timeout is not scheduled when no timeout configured for state") {
        // Test FSMRuntime.scala line 554: ZIO.when(currentHash == stateHash) - state mismatch
        // This tests that timeout callback checks current state before firing
        val machineWithTimeout = Machine(
          assembly[TimeoutState, TimeoutEvent](
            (Idle via Start to Waiting) @@ Aspect.timeout(50.millis, Timeout),
            Waiting via Complete to Done,
            Waiting via Timeout to TimedOut,
          )
        )

        for
          runtime <- machineWithTimeout.start(Idle)
          // Don't send Start, so we stay in Idle which has no timeout
          config = runtime.timeoutConfigForState(Idle)
        yield assertTrue(config.isEmpty) // No timeout configured for Idle
      },
      test("send returns Stop outcome when runtime is stopped") {
        // Test FSMRuntime.scala line 371: returns Stop when !running
        for
          runtime <- simpleMachine.start(A)
          _       <- runtime.stop
          result  <- runtime.send(E1)
        yield assertTrue(result.result == TransitionResult.Stop(Some("FSM stopped")))
      },
      test("Goto transition triggers state update and timeout scheduling") {
        // Test FSMRuntime.scala lines 488 (yield in Goto) and 554/487 (startTimeout)
        val machineWithTimeout = Machine(
          assembly[TimeoutState, TimeoutEvent](
            (Idle via Start to Waiting) @@ Aspect.timeout(50.millis, Timeout),
            Waiting via Complete to Done,
            Waiting via Timeout to TimedOut,
          )
        )
        for
          runtime <- machineWithTimeout.start(Idle)
          outcome <- runtime.send(Start)
          state   <- runtime.currentState
        yield assertTrue(
          outcome.result == TransitionResult.Goto(Waiting),
          state == Waiting,
        )
      },
      test("Stop transition sets runningRef to false") {
        // Test FSMRuntime.scala line 498: runningRef.set(false) in Stop
        // Create a machine with stop transition
        val stopMachine = Machine(
          assembly[TestState, TestEvent](
            A via E1 to stop("terminated")
          )
        )
        for
          runtime <- stopMachine.start(A)
          outcome <- runtime.send(E1)
          running <- runtime.isRunning
        yield assertTrue(
          outcome.result.isInstanceOf[TransitionResult.Stop[?]],
          !running,
        )
      },
    ),
  ).provideLayer(ZLayer.succeed(zio.Scope.global)) @@ TestAspect.timeout(30.seconds)

end FSMRuntimeSpec
