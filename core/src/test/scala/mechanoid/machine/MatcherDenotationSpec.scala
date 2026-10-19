package mechanoid.machine

import mechanoid.*
import mechanoid.machine.Macros.hashForType
import mechanoid.core.{InvalidTransitionError, PayloadLeafMismatchError}
import zio.*
import zio.test.*

/** A matcher denotes exactly the leaves of its type. Unions and aliases are leaf sets. Parents are not `state` or
  * `event`.
  */
object MatcherDenotationSpec extends ZIOSpecDefault:

  sealed trait Letter derives Finite
  case object A extends Letter
  case object B extends Letter
  case object C extends Letter

  type AB    = A.type | B.type
  type ALeaf = A.type

  sealed trait Doc derives Finite
  case object Draft     extends Doc
  sealed trait InReview extends Doc
  case object Pending   extends InReview
  case object Under     extends InReview

  type Review = InReview

  enum Cell derives Finite:
    case DraftCell(n: Int)
    case LiveCell(n: Int)
    case DoneCell(n: Int)

  enum Page derives Finite:
    case DraftPage(name: String)
    case LivePage(name: String)
    case ArchivedPage(name: String)

  enum PageEvent derives Finite:
    case SetName(name: String)

  import Cell.*
  import Page.*
  import PageEvent.*

  type Named = DraftPage | LivePage

  val letters = summon[Finite[Letter]]
  val docs    = summon[Finite[Doc]]
  val cells   = summon[Finite[Cell]]
  val pages   = summon[Finite[Page]]
  val events  = summon[Finite[PageEvent]]

  val pageMachine: Machine[Page, PageEvent] = Machine(
    assembly[Page, PageEvent](
      (all[Named] via event[SetName]).to(stay) { (s, e) =>
        s match
          case _: DraftPage => DraftPage(e.name)
          case _: LivePage  => LivePage(e.name)
      }
    )
  )

  val wrongLeaf: Machine[Page, PageEvent] = Machine(
    assembly[Page, PageEvent](
      (all[Named] via event[SetName]).to(stay) { (_, e) => ArchivedPage(e.name) }
    )
  )

  def spec = suite("matcher denotation")(
    suite("unions and aliases")(
      test("all[A | B] is those leaves, in that order") {
        val ha = hashForType[A.type]
        val hb = hashForType[B.type]
        assertTrue(
          ha == letters.caseHash(A),
          hb == letters.caseHash(B),
          all[A.type | B.type].hashes == Set(ha, hb),
          all[A.type | B.type].names == List("A", "B"),
          all[A.type | A.type].hashes == Set(ha),
          all[A.type | A.type].names == List("A"),
        )
      },
      test("a type alias of a union denotes the same leaves") {
        assertTrue(
          all[AB].hashes == all[A.type | B.type].hashes,
          all[AB].names == List("A", "B"),
        )
      },
      test("a type alias of a sealed parent denotes that parent's leaves") {
        assertTrue(
          all[Review].hashes == all[InReview].hashes,
          all[Review].names == all[InReview].names,
          all[InReview].hashes == Set(docs.caseHash(Pending), docs.caseHash(Under)),
        )
      },
      test("a type alias of one leaf denotes that leaf") {
        val hd = hashForType[Draft.type]
        assertTrue(
          hd == docs.caseHash(Draft),
          all[ALeaf].hashes == Set(hashForType[A.type]),
          all[Draft.type].hashes == Set(hd),
          all[Draft.type].names == List("Draft"),
        )
      },
      test("a union of a parent and a leaf is the parent's leaves plus that leaf") {
        val hd = hashForType[Draft.type]
        assertTrue(
          all[InReview | Draft.type].hashes == all[InReview].hashes ++ Set(hd),
          all[InReview | Draft.type].names == all[InReview].names :+ "Draft",
        )
      },
      test("a union of parameterized cases matches Finite.caseHash") {
        val draft = DraftCell(0)
        val live  = LiveCell(0)
        assertTrue(
          hashForType[DraftCell] == cells.caseHash(draft),
          hashForType[LiveCell] == cells.caseHash(live),
          all[DraftCell | LiveCell].hashes == Set(cells.caseHash(draft), cells.caseHash(live)),
          all[DraftCell | LiveCell].names == List("DraftCell", "LiveCell"),
        )
      },
    ),
    suite("rejects a type that is not those leaves")(
      test("all[NonSealed] does not compile") {
        val result = typeCheck("""
          import mechanoid.machine.*
          trait Box
          all[Box]
        """)
        assertZIO(result)(Assertion.isLeft)
      },
      test("all of a sealed type with no cases does not compile") {
        val result = typeCheck("""
          import mechanoid.machine.*
          sealed trait Empty
          all[Empty]
        """)
        assertZIO(result)(Assertion.isLeft)
      },
      test("state of a one-leaf parent tells you to write all[InReview]") {
        val result = typeCheck("""
          import mechanoid.machine.*
          import mechanoid.core.Finite
          sealed trait S derives Finite
          sealed trait InReview extends S
          case object Pending extends InReview
          state[InReview]
        """)
        assertZIO(result)(Assertion.isLeft(Assertion.containsString("all[InReview]")))
      },
      test("event[ParentEvent] tells you to write all[ParentEvent]") {
        val result = typeCheck("""
          import mechanoid.machine.*
          import mechanoid.core.Finite
          sealed trait E derives Finite
          sealed trait ParentEvent extends E
          case object Go extends ParentEvent
          event[ParentEvent]
        """)
        assertZIO(result)(Assertion.isLeft(Assertion.containsString("all[ParentEvent]")))
      },
      test("state of a union tells you to write all") {
        val result = typeCheck("""
          import mechanoid.machine.*
          import mechanoid.core.Finite
          sealed trait S derives Finite
          case object A extends S
          case object B extends S
          type Pair = A.type | B.type
          state[Pair]
        """)
        assertZIO(result)(Assertion.isLeft(Assertion.containsString("all[Pair]")))
      },
      test("event of a union tells you to write all") {
        val result = typeCheck("""
          import mechanoid.machine.*
          import mechanoid.core.Finite
          sealed trait E derives Finite
          case object Go extends E
          case object Stop extends E
          type Pair = Go.type | Stop.type
          event[Pair]
        """)
        assertZIO(result)(Assertion.isLeft(Assertion.containsString("all[Pair]")))
      },
      test("an opaque alias of a union is not a leaf set") {
        val result = typeCheck("""
          import mechanoid.machine.*
          sealed trait S
          case object A extends S
          case object B extends S
          object Hide:
            opaque type G = A.type | B.type
          val denied = all[Hide.G]
        """)
        assertZIO(result)(Assertion.isLeft)
      },
    ),
    suite("payload stay on a union")(
      test("Draft and Live rewrite the name and stay on that leaf") {
        ZIO.scoped {
          for
            draftFsm <- pageMachine.start(DraftPage("old"))
            _        <- draftFsm.send(SetName("new"))
            draft    <- draftFsm.currentState
            liveFsm  <- pageMachine.start(LivePage("old"))
            _        <- liveFsm.send(SetName("new"))
            live     <- liveFsm.currentState
          yield assertTrue(draft == DraftPage("new"), live == LivePage("new"))
        }
      },
      test("Archived has no SetName edge and appends nothing") {
        val archived = ArchivedPage("keep")
        ZIO.scoped {
          for
            fsm <- pageMachine.start(archived)
            err <- fsm.send(SetName("new")).either
            s   <- fsm.currentState
            n   <- fsm.lastSequenceNr
          yield err match
            case Left(_: InvalidTransitionError[?, ?]) => assertTrue(s == archived, n == 0L)
            case _                                     => assertTrue(false)
        }
      },
      test("a stay reducer that returns another leaf is PayloadLeafMismatchError and appends nothing") {
        val draft = DraftPage("old")
        ZIO.scoped {
          for
            fsm <- wrongLeaf.start(draft)
            err <- fsm.send(SetName("new")).either
            s   <- fsm.currentState
            n   <- fsm.lastSequenceNr
          yield err match
            case Left(_: PayloadLeafMismatchError[?]) => assertTrue(s == draft, n == 0L)
            case _                                    => assertTrue(false)
        }
      },
      test("transitionMeta has one Stay row per leaf of the union") {
        val setName = events.caseHash(SetName(""))
        val rows    = pageMachine.transitionMeta.filter(_.eventCaseHash == setName)
        assertTrue(
          rows.map(_.fromStateCaseHash).toSet == Set(pages.caseHash(DraftPage("")), pages.caseHash(LivePage(""))),
          rows.length == 2,
          rows.forall(_.kind == mechanoid.visualization.TransitionKind.Stay),
          MachineGraph.hasEdge(pageMachine, DraftPage("a"), SetName("b")),
          MachineGraph.hasEdge(pageMachine, LivePage("a"), SetName("b")),
          !MachineGraph.hasEdge(pageMachine, ArchivedPage("a"), SetName("b")),
        )
      },
    ),
    suite("duplicate leaf edges")(
      test("all[Parent] plus one of its leaves is a duplicate") {
        val result = typeCheck("""
          import mechanoid.machine.*
          import mechanoid.core.Finite
          sealed trait S derives Finite
          case object Draft extends S
          sealed trait InReview extends S
          case object Pending extends InReview
          case object Under extends InReview
          enum E derives Finite { case Cancel }
          assembly[S, E](all[InReview] via E.Cancel to Draft, Pending via E.Cancel to Draft)
        """)
        assertZIO(result)(Assertion.isLeft)
      },
      test("all[A | B] plus the same leaf and event does not compile") {
        val result = typeCheck("""
          import mechanoid.machine.*
          import mechanoid.core.Finite
          sealed trait S derives Finite
          case object A extends S
          case object B extends S
          case object C extends S
          enum E derives Finite { case E1 }
          assembly[S, E](all[A.type | B.type] via E.E1 to C, A via E.E1 to C)
        """)
        assertZIO(result)(Assertion.isLeft)
      },
      test("Aspect.overriding allows the later leaf edge") {
        val result = typeCheck("""
          import mechanoid.machine.*
          import mechanoid.core.Finite
          sealed trait S derives Finite
          case object A extends S
          case object B extends S
          case object C extends S
          enum E derives Finite { case E1 }
          assembly[S, E](
            all[A.type | B.type] via E.E1 to C,
            (A via E.E1 to C) @@ Aspect.overriding,
          )
        """)
        assertZIO(result)(Assertion.isRight)
      },
    ),
  )
end MatcherDenotationSpec
