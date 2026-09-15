package mechanoid.machine

import zio.*
import zio.test.*
import mechanoid.*
import mechanoid.persistence.{Alias, AliasExtractor}
import mechanoid.runtime.locking.OptimisticLockingStrategy
import mechanoid.runtime.timeout.FiberTimeoutStrategy
import mechanoid.stores.{InMemoryEventStore, InMemoryInstanceIndex}
import mechanoid.visualization.TransitionKind

/** Algebraic laws for computed `to` / `stay` payloads. Finite graph stays static; reducers fold snapshots. */
object PayloadLawsSpec extends ZIOSpecDefault:

  enum Cell derives Finite:
    case Draft(n: Int, name: String)
    case Live(n: Int, name: String, tags: List[String])
    case Done(name: String)

  enum Pulse derives Finite:
    case SetName(name: String)
    case Add(delta: Int)
    case Tag(id: String)
    case Launch
    case Finish
    case Tick
    case Beat
    case Reject

  import Cell.*
  import Pulse.*

  val se = summon[Finite[Cell]]
  val ee = summon[Finite[Pulse]]

  val machine: Machine[Cell, Pulse] = Machine(
    assembly[Cell, Pulse](
      (state[Draft] via event[SetName]).to(stay) { (d, e) => Draft(d.n, e.name) },
      (state[Draft] via event[Add]).to(stay) { (d, e) => Draft(d.n + e.delta, d.name) },
      (state[Draft] via Launch).to[Live] { (d, _) => Live(d.n, d.name, Nil) },
      (state[Draft] via Reject).to(stay) { (d, _) => ZIO.fail("rejected").as(d) },
      (state[Live] via event[Tag]).to(stay) { (l, e) => Live(l.n, l.name, l.tags :+ e.id) },
      (state[Live] via event[Add]).to(stay) { (l, e) => Live(l.n + e.delta, l.name, l.tags) },
      state[Live] via Tick to stay,
      (state[Live] via Beat).to[Live] { (l, _) => l },
      (state[Live] via Finish).to[Done] { (l, _) => Done(l.name) },
    )
  )

  val timedMachine: Machine[Cell, Pulse] = Machine(
    assembly[Cell, Pulse](
      ((state[Draft] via Launch).to[Live] { (d, _) => Live(d.n, d.name, Nil) }) @@
        Aspect.timeout(10.seconds, Tick),
      (state[Live] via event[Tag]).to(stay) { (l, e) => Live(l.n, l.name, l.tags :+ e.id) },
      (state[Live] via Beat).to[Live] { (l, _) => l },
      (state[Live] via Tick).to[Done] { (l, _) => Done(l.name) },
    )
  )

  val extractor: AliasExtractor[Cell] = AliasExtractor {
    case Live(_, _, tags) => Chunk.fromIterable(tags.map(id => Alias("tag", id)))
    case _                => Chunk.empty
  }

  enum StepError:
    case NoEdge
    case Rejected

  def foldOne(s: Cell, e: Pulse): Either[StepError, Cell] = (s, e) match
    case (d: Draft, SetName(name)) => Right(Draft(d.n, name))
    case (d: Draft, Add(delta))    => Right(Draft(d.n + delta, d.name))
    case (d: Draft, Launch)        => Right(Live(d.n, d.name, Nil))
    case (_: Draft, Reject)        => Left(StepError.Rejected)
    case (l: Live, Tag(id))        => Right(Live(l.n, l.name, l.tags :+ id))
    case (l: Live, Add(delta))     => Right(Live(l.n + delta, l.name, l.tags))
    case (l: Live, Tick)           => Right(l)
    case (l: Live, Beat)           => Right(l)
    case (l: Live, Finish)         => Right(Done(l.name))
    case _                         => Left(StepError.NoEdge)

  def foldAll(start: Cell, events: List[Pulse]): Either[StepError, Cell] =
    events.foldLeft[Either[StepError, Cell]](Right(start)) { (acc, e) =>
      acc.flatMap(foldOne(_, e))
    }

  val genName: Gen[Any, String]  = Gen.alphaNumericStringBounded(0, 8)
  val genDelta: Gen[Any, Int]    = Gen.int(-20, 20)
  val genTagId: Gen[Any, String] = Gen.alphaNumericStringBounded(1, 6)

  val genDraft: Gen[Any, Draft] =
    for
      n    <- genDelta
      name <- genName
    yield Draft(n, name)

  val genLive: Gen[Any, Live] =
    for
      n    <- genDelta
      name <- genName
      tags <- Gen.listOfBounded(0, 4)(genTagId)
    yield Live(n, name, tags)

  val genCell: Gen[Any, Cell] =
    Gen.oneOf(genDraft, genLive, genName.map(Done(_)))

  val genPulse: Gen[Any, Pulse] =
    Gen.oneOf(
      genName.map(SetName(_)),
      genDelta.map(Add(_)),
      genTagId.map(Tag(_)),
      Gen.const(Launch),
      Gen.const(Finish),
      Gen.const(Tick),
      Gen.const(Beat),
      Gen.const(Reject),
    )

  val genDraftPulse: Gen[Any, Pulse] =
    Gen.oneOf(genName.map(SetName(_)), genDelta.map(Add(_)))

  val genLivePulse: Gen[Any, Pulse] =
    Gen.oneOf(genTagId.map(Tag(_)), genDelta.map(Add(_)), Gen.const(Tick), Gen.const(Beat))

  val genValidScript: Gen[Any, List[Pulse]] =
    for
      draftOps <- Gen.listOfBounded(0, 8)(genDraftPulse)
      launch   <- Gen.boolean
      liveOps  <- if launch then Gen.listOfBounded(0, 8)(genLivePulse) else Gen.const(List.empty[Pulse])
      finish   <- if launch then Gen.boolean else Gen.const(false)
    yield draftOps ++ (if launch then Launch :: liveOps ++ (if finish then List(Finish) else Nil) else Nil)

  def sendAll(initial: Cell, events: List[Pulse]): ZIO[Scope, MechanoidError, (Cell, Long, List[Cell])] =
    for
      fsm <- machine.start(initial)
      _   <- ZIO.foreachDiscard(events)(fsm.send)
      s   <- fsm.currentState
      n   <- fsm.lastSequenceNr
      h   <- fsm.history
    yield (s, n, h)

  def persistentLayers(store: InMemoryEventStore[String, Cell, Pulse]) =
    ZLayer.succeed(store) ++
      FiberTimeoutStrategy.layer[String] ++
      OptimisticLockingStrategy.layer[String]

  def spec = suite("PayloadLaws")(
    suite("Finite leaf identity")(
      test("type hash matches Finite.caseHash for every leaf") {
        assertTrue(
          mechanoid.machine.Macros.hashForType[Draft] == se.caseHash(Draft(0, "")),
          mechanoid.machine.Macros.hashForType[Live] == se.caseHash(Live(0, "", Nil)),
          mechanoid.machine.Macros.hashForType[Done] == se.caseHash(Done("")),
        )
      },
      test("assembly target hash is the declared leaf, independent of payload") {
        val launch = machine.transitionMeta.find { m =>
          m.fromStateCaseHash == se.caseHash(Draft(0, "")) &&
          m.eventCaseHash == ee.caseHash(Launch)
        }
        val setName = machine.transitionMeta.find { m =>
          m.fromStateCaseHash == se.caseHash(Draft(0, "")) &&
          m.eventCaseHash == ee.caseHash(SetName(""))
        }
        val beat = machine.transitionMeta.find { m =>
          m.fromStateCaseHash == se.caseHash(Live(0, "", Nil)) &&
          m.eventCaseHash == ee.caseHash(Beat)
        }
        assertTrue(
          launch.exists(m =>
            m.kind == TransitionKind.Goto && m.targetStateCaseHash.contains(se.caseHash(Live(0, "", Nil)))
          ),
          setName.exists(m => m.kind == TransitionKind.Stay && m.targetStateCaseHash.isEmpty),
          beat.exists(m =>
            m.kind == TransitionKind.Goto && m.targetStateCaseHash.contains(se.caseHash(Live(0, "", Nil)))
          ),
        )
      },
    ),
    suite("edge lands on the declared leaf")(
      test("every (state, event) pair is Goto-to-declared, Stay-same-leaf, action fail, or no edge") {
        check(genCell, genPulse) { (s, e) =>
          val meta = machine.transitionMeta.find { m =>
            m.fromStateCaseHash == se.caseHash(s) && m.eventCaseHash == ee.caseHash(e)
          }
          ZIO
            .scoped {
              machine.start(s).flatMap(_.send(e)).either
            }
            .map { result =>
              (meta, result, foldOne(s, e)) match
                case (None, Left(_: InvalidTransitionError[?, ?]), Left(StepError.NoEdge)) =>
                  assertTrue(true)
                case (Some(m), Right(out), Right(expected)) if m.kind == TransitionKind.Stay =>
                  out.result match
                    case TransitionResult.Stay(ns) =>
                      assertTrue(ns == expected, se.caseHash(ns) == se.caseHash(s), m.targetStateCaseHash.isEmpty)
                    case other => assertTrue(other == expected)
                case (Some(m), Right(out), Right(expected)) if m.kind == TransitionKind.Goto =>
                  out.result match
                    case TransitionResult.Goto(ns) =>
                      assertTrue(
                        ns == expected,
                        m.targetStateCaseHash.contains(se.caseHash(ns)),
                      )
                    case other => assertTrue(other == expected)
                case (Some(_), Left(_: ActionFailedError[?]), Left(StepError.Rejected)) =>
                  assertTrue(true)
                case other =>
                  assertTrue(other == null)
            }
        }
      }
    ),
    suite("fold equivalence")(
      test("valid scripts reconstruct the pure fold, Stay does not push history") {
        check(genDraft, genValidScript) { (start, events) =>
          val expected = foldAll(start, events)
          ZIO
            .scoped {
              sendAll(start, events).either
            }
            .map {
              case Right((got, seq, history)) =>
                val gotos = events.count {
                  case Launch | Beat | Finish => true
                  case _                      => false
                }
                assertTrue(
                  expected.contains(got),
                  seq == events.size.toLong,
                  history.size == gotos,
                )
              case Left(err) =>
                assertTrue(err == null)
            }
        }
      },
      test("constant to ignores current payload") {
        val constant = Machine(
          assembly[Cell, Pulse](
            state[Draft] via Launch to Live(9, "fixed", List("nope"))
          )
        )
        check(genDraft) { d =>
          ZIO.scoped {
            for
              fsm <- constant.start(d)
              _   <- fsm.send(Launch)
              s   <- fsm.currentState
            yield assertTrue(s == Live(9, "fixed", List("nope")))
          }
        }
      },
      test("witness dummy is not stored") {
        val computed = Machine(
          assembly[Cell, Pulse](
            (state[Draft] via Launch).to[Live] { (d, _) => Live(d.n, d.name, Nil) }
          )
        )
        check(genDraft) { d =>
          ZIO.scoped {
            for
              fsm <- computed.start(d)
              _   <- fsm.send(Launch)
              s   <- fsm.currentState
            yield assertTrue(s == Live(d.n, d.name, Nil))
          }
        }
      },
    ),
    suite("action before append")(
      test("reducer fail does not append and does not change state") {
        check(genDraft) { d =>
          ZIO.scoped {
            for
              fsm <- machine.start(d)
              err <- fsm.send(Reject).either
              s   <- fsm.currentState
              n   <- fsm.lastSequenceNr
            yield err match
              case Left(_: ActionFailedError[?]) => assertTrue(s == d, n == 0L)
              case _                             => assertTrue(false)
          }
        }
      },
      test("missing edge is InvalidTransitionError and does not append") {
        check(genDraft) { d =>
          ZIO.scoped {
            for
              fsm <- machine.start(d)
              err <- fsm.send(Finish).either
              s   <- fsm.currentState
              n   <- fsm.lastSequenceNr
            yield err match
              case Left(_: InvalidTransitionError[?, ?]) => assertTrue(s == d, n == 0L)
              case _                                     => assertTrue(false)
          }
        }
      },
      test("wrong stay leaf is PayloadLeafMismatchError and does not append") {
        val wrong = Machine(
          assembly[Cell, Pulse](
            (all[Cell] via Tick).to(stay) { (_, _) => Done("x") }
          )
        )
        check(genDraft) { d =>
          ZIO.scoped {
            for
              fsm <- wrong.start(d)
              err <- fsm.send(Tick).either
              s   <- fsm.currentState
              n   <- fsm.lastSequenceNr
            yield err match
              case Left(_: PayloadLeafMismatchError[?]) => assertTrue(s == d, n == 0L)
              case _                                    => assertTrue(false)
          }
        }
      },
    ),
    suite("Stay vs Goto lifecycle")(
      test("stay rewrite does not push history or change lastTransitionAt") {
        check(genDraft, genName) { (d, name) =>
          ZIO.scoped {
            for
              fsm    <- machine.start(d)
              before <- fsm.state
              _      <- fsm.send(SetName(name))
              after  <- fsm.state
            yield assertTrue(
              after.current == Draft(d.n, name),
              after.history == before.history,
              after.lastTransitionAt == before.lastTransitionAt,
            )
          }
        }
      },
      test("self-Goto pushes history") {
        check(genLive) { l =>
          ZIO.scoped {
            for
              fsm <- machine.start(l)
              _   <- fsm.send(Beat)
              st  <- fsm.state
            yield assertTrue(st.current == l, st.history.headOption.contains(l))
          }
        }
      },
      test("stay rewrite does not reset timeout") {
        check(Gen.int(1, 8), Gen.listOfBounded(0, 4)(genTagId)) { (elapsed, tags) =>
          ZIO.scoped {
            for
              fsm <- timedMachine.start(Draft(1, "n"))
              _   <- fsm.send(Launch)
              _   <- TestClock.adjust(elapsed.seconds)
              _   <- ZIO.foreachDiscard(tags.map(Tag(_)))(fsm.send)
              _   <- TestClock.adjust((10 - elapsed).seconds + 50.millis)
              _   <- ZIO.yieldNow
              s   <- fsm.currentState
            yield assertTrue(s == Done("n"))
          }
        }
      },
      test("self-Goto resets timeout") {
        ZIO.scoped {
          for
            fsm <- timedMachine.start(Draft(1, "n"))
            _   <- fsm.send(Launch)
            _   <- TestClock.adjust(4.seconds)
            _   <- fsm.send(Beat)
            _   <- TestClock.adjust(6.seconds)
            _   <- ZIO.yieldNow
            mid <- fsm.currentState
            _   <- TestClock.adjust(5.seconds)
            _   <- ZIO.yieldNow
            end <- fsm.currentState
          yield assertTrue(mid == Live(1, "n", Nil), end == Done("n"))
        }
      },
    ),
    suite("replay")(
      test("recovering from the event log equals the live fold") {
        check(genDraft, genValidScript) { (start, events) =>
          val expected = foldAll(start, events).toOption.get
          for
            store <- InMemoryEventStore.make[String, Cell, Pulse]()
            layers = persistentLayers(store)
            _ <- ZIO
              .scoped {
                FSMRuntime("p1", machine, start).flatMap { fsm =>
                  ZIO.foreachDiscard(events)(fsm.send)
                }
              }
              .provide(layers)
            recovered <- ZIO
              .scoped {
                FSMRuntime("p1", machine, start).flatMap(_.currentState)
              }
              .provide(layers)
          yield assertTrue(recovered == expected)
          end for
        }
      }
    ),
    suite("aliases")(
      test("stay rewrite binds added tags and unbinds removed ones") {
        check(Gen.listOfBounded(1, 5)(genTagId)) { tags =>
          val distinct = tags.distinct
          ZIO.scoped {
            for
              store <- InMemoryEventStore.make[String, Cell, Pulse]()
              index <- InMemoryInstanceIndex.make[String]
              layers = persistentLayers(store) ++ ZLayer.succeed[mechanoid.persistence.InstanceIndex[String]](index)
              _ <- ZIO
                .scoped {
                  FSMRuntime("p1", machine, Draft(0, "n"), extractor).flatMap { fsm =>
                    fsm.send(Launch) *> ZIO.foreachDiscard(distinct.map(Tag(_)))(fsm.send)
                  }
                }
                .provide(layers)
              bound <- index.aliasesOf("p1", Some("tag"))
            yield assertTrue(bound.toSet == distinct.map(id => Alias("tag", id)).toSet)
          }
        }
      },
      test("alias uniqueness clash on stay rewrite does not append") {
        ZIO.scoped {
          for
            store <- InMemoryEventStore.make[String, Cell, Pulse]()
            index <- InMemoryInstanceIndex.make[String]
            _     <- index.bind(Alias("tag", "taken"), "other")
            layers = persistentLayers(store) ++ ZLayer.succeed[mechanoid.persistence.InstanceIndex[String]](index)
            result <- ZIO
              .scoped {
                FSMRuntime("p1", machine, Live(0, "n", Nil), extractor).flatMap(_.send(Tag("taken")).either)
              }
              .provide(layers)
            seq <- store.highestSequenceNr("p1")
          yield result match
            case Left(_: UniqueAliasError) => assertTrue(seq == 0L)
            case other                     => assertTrue(other == null)
        }
      },
    ),
  ) @@ TestAspect.samples(50)
end PayloadLawsSpec
