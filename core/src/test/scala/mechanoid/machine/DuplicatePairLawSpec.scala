package mechanoid.machine

import mechanoid.*
import zio.test.*

/** Duplicate detection and `Machine.fromSpecs` must agree on `(leaf, event)` pairs. */
object DuplicatePairLawSpec extends ZIOSpecDefault:

  sealed trait Phase derives Finite
  sealed trait Open extends Phase
  case object Draft extends Open
  case object Live  extends Open
  case object Gone  extends Phase

  enum Cmd derives Finite:
    case SetName(n: String)
    case SetWindow(n: String)
    case Archive
    case Stop

  enum Doc derives Finite:
    case Draft(n: String)
    case Live(n: String)
    case Gone(n: String)

  enum Act derives Finite:
    case SetName(n: String)
    case SetWindow(n: String)

  type Renamable = Doc.Draft | Doc.Live
  type Windowed  = Doc.Draft
  type Closing   = Cmd.Archive.type | Cmd.Stop.type

  import Cmd.*
  import Doc.{Draft as DocDraft, Live as DocLive}
  import Act.{SetName as Rename, SetWindow as Resize}

  inline def phaseLeafValue   = assembly[Phase, Cmd](Draft via Archive to Gone)
  inline def phaseStateValue  = assembly[Phase, Cmd](state[Draft.type] via Archive to Gone)
  inline def phaseAllValue    = assembly[Phase, Cmd](all[Open] via Archive to Gone)
  inline def phaseAnyOfValue  = assembly[Phase, Cmd](anyOf(Draft, Live) via Archive to Gone)
  inline def phaseLeafEvent   = assembly[Phase, Cmd](Draft via event[SetName] to Gone)
  inline def phaseStateEvent  = assembly[Phase, Cmd](state[Draft.type] via event[SetName] to Gone)
  inline def phaseAllEvent    = assembly[Phase, Cmd](all[Open] via event[SetName] to Gone)
  inline def phaseUnionEvent  = assembly[Phase, Cmd](all[Draft.type | Live.type] via event[SetWindow] to Gone)
  inline def phaseAliasEvent  = assembly[Phase, Cmd](all[Open] via event[Archive.type] to Gone)
  inline def phaseAnyOfEvent  = assembly[Phase, Cmd](anyOf(Draft, Live) via event[SetName] to Gone)
  inline def phaseLeafViaAll  = assembly[Phase, Cmd](Draft viaAll all[Closing] to Gone)
  inline def phaseStateViaAll = assembly[Phase, Cmd](state[Draft.type] viaAll all[Closing] to Gone)
  inline def phaseAllViaAll   = assembly[Phase, Cmd](all[Open] viaAll all[Closing] to Gone)
  inline def phaseLeafViaAny  = assembly[Phase, Cmd](Draft viaAnyOf anyOfEvents(Archive, Stop) to Gone)
  inline def phaseStateViaAny = assembly[Phase, Cmd](state[Live.type] viaAnyOf anyOfEvents(Archive, Stop) to Gone)
  inline def phaseAllViaAny   = assembly[Phase, Cmd](all[Open] viaAnyOf anyOfEvents(Archive, Stop) to Gone)
  inline def phaseAnyOfViaAny = assembly[Phase, Cmd](anyOf(Draft, Live) viaAnyOf anyOfEvents(Archive, Stop) to Gone)
  inline def docLeafEvent     = assembly[Doc, Act](DocDraft("a") via event[Rename] to stay)
  inline def docStateEvent    = assembly[Doc, Act](state[Doc.Draft] via event[Rename] to stay)
  inline def docUnionEvent    = assembly[Doc, Act](all[Doc.Draft | Doc.Live] via event[Rename] to stay)
  inline def docAliasEvent    = assembly[Doc, Act](all[Renamable] via event[Rename] to stay)
  inline def docWindowEvent   = assembly[Doc, Act](all[Windowed] via event[Resize] to stay)
  inline def docAnyOfEvent    = assembly[Doc, Act](anyOf(DocDraft("a"), DocLive("b")) via event[Rename] to stay)
  inline def docAllViaAll     = assembly[Doc, Act](all[Renamable] viaAll all[Act.SetName | Act.SetWindow] to stay)
  inline def docStateViaAll   = assembly[Doc, Act](state[Doc.Draft] viaAll all[Act.SetName | Act.SetWindow] to stay)
  inline def docLeafViaAny    = assembly[Doc, Act](DocDraft("a") viaAnyOf anyOfEvents(Rename("a"), Resize("b")) to stay)
  inline def renamableAsm     =
    assembly[Doc, Act]((all[Renamable] via event[Rename]).to(stay)((s, _) => s))
  inline def windowedAsm =
    assembly[Doc, Act]((all[Windowed] via event[Resize]).to(stay)((s, _) => s))
  inline def overlapAsm    = renamableAsm ++ windowedAsm
  inline def overriddenAsm = assembly[Doc, Act](
    (all[Renamable] via event[Rename]).to(stay)((s, _) => s),
    ((state[Doc.Draft] via event[Rename]).to[Doc.Gone]((_, e) => Doc.Gone(e.n))) @@ Aspect.overriding,
  )

  def metaPairs(machine: Machine[?, ?]): Set[(Int, Int)] =
    machine.transitionMeta.map(row => (row.fromStateCaseHash, row.eventCaseHash)).toSet

  def namesOf(asm: Assembly[?, ?]): List[String] =
    asm.hashInfos.flatMap(info => info.stateNames ++ info.eventNames)

  def specPairs(spec: TransitionSpec[?, ?, ?]): Set[(Int, Int)] =
    spec.stateHashes.flatMap(state => spec.eventHashes.map(event => (state, event)))

  def infoPairs(info: IncludedHashInfo): Set[(Int, Int)] =
    info.stateHashes.flatMap(state => info.eventHashes.map(event => (state, event)))

  def agrees(name: String, asm: Assembly[?, ?], machine: Machine[?, ?]): Option[String] =
    val recorded = asm.hashInfos.map(infoPairs)
    val runtime  = asm.specs.map(specPairs)
    val names    = namesOf(asm)
    val badName  = names.exists(n => n == "<init>" || n == "?" || n == "<none>" || n.isEmpty)
    val aligned  = recorded.length == runtime.length && recorded.zip(runtime).forall((left, right) => left == right)
    if aligned && recorded.flatten.toSet == metaPairs(machine) && names.nonEmpty && !badName then None
    else
      Some(
        s"$name recorded=$recorded runtime=$runtime meta=${metaPairs(machine)} names=$names"
      )
  end agrees

  inline def phaseDrift(name: String, inline asm: Assembly[Phase, Cmd]): Option[String] =
    agrees(name, asm, Machine(asm))

  inline def docDrift(name: String, inline asm: Assembly[Doc, Act]): Option[String] =
    agrees(name, asm, Machine(asm))

  def spec = suite("duplicate pairs")(
    test("recorded pairs are the pairs the machine registers") {
      val failed = List(
        phaseDrift("leaf via value", phaseLeafValue),
        phaseDrift("state via value", phaseStateValue),
        phaseDrift("all parent via value", phaseAllValue),
        phaseDrift("anyOf via value", phaseAnyOfValue),
        phaseDrift("leaf via event", phaseLeafEvent),
        phaseDrift("state via event", phaseStateEvent),
        phaseDrift("all parent via event", phaseAllEvent),
        phaseDrift("all union via event", phaseUnionEvent),
        phaseDrift("all parent via event object", phaseAliasEvent),
        phaseDrift("anyOf via event", phaseAnyOfEvent),
        phaseDrift("leaf viaAll", phaseLeafViaAll),
        phaseDrift("state viaAll", phaseStateViaAll),
        phaseDrift("all parent viaAll", phaseAllViaAll),
        phaseDrift("leaf viaAnyOf", phaseLeafViaAny),
        phaseDrift("state viaAnyOf", phaseStateViaAny),
        phaseDrift("all parent viaAnyOf", phaseAllViaAny),
        phaseDrift("anyOf viaAnyOf", phaseAnyOfViaAny),
        docDrift("case leaf via event", docLeafEvent),
        docDrift("state case via event", docStateEvent),
        docDrift("all union via event", docUnionEvent),
        docDrift("all alias via event", docAliasEvent),
        docDrift("all alias via other", docWindowEvent),
        docDrift("anyOf case via event", docAnyOfEvent),
        docDrift("all alias viaAll", docAllViaAll),
        docDrift("state case viaAll", docStateViaAll),
        docDrift("case leaf viaAnyOf", docLeafViaAny),
      ).flatten
      assertTrue(failed.isEmpty)
    },
    test("fromSpecs does not replace a pair when the later spec is not an override") {
      val phases = summon[Finite[Phase]]
      val cmds   = summon[Finite[Cmd]]
      val first  = TransitionSpec.goto[Phase, Cmd, Phase](
        Set(phases.caseHash(Draft)),
        Set(cmds.caseHash(Archive)),
        List("Draft"),
        List("Archive"),
        Gone,
      )
      val second = TransitionSpec.goto[Phase, Cmd, Phase](
        Set(phases.caseHash(Draft)),
        Set(cmds.caseHash(Archive)),
        List("Draft"),
        List("Archive"),
        Live,
      )
      Machine.fromSpecs[Phase, Cmd](List(first, second)) match
        case Left(AssemblyDefect.DuplicateTransition(state, event)) =>
          assertTrue(state == "Draft", event == "Archive")
        case other =>
          assertTrue(other == Left(AssemblyDefect.DuplicateTransition("Draft", "Archive")))
    },
    test("fromSpecs keeps the later leaf when the later spec overrides") {
      val phases = summon[Finite[Phase]]
      val cmds   = summon[Finite[Cmd]]
      val first  = TransitionSpec.goto[Phase, Cmd, Phase](
        Set(phases.caseHash(Draft)),
        Set(cmds.caseHash(Archive)),
        List("Draft"),
        List("Archive"),
        Gone,
      )
      val second = TransitionSpec
        .goto[Phase, Cmd, Phase](
          Set(phases.caseHash(Draft)),
          Set(cmds.caseHash(Archive)),
          List("Draft"),
          List("Archive"),
          Live,
        )
        .copy(isOverride = true)
      Machine.fromSpecs[Phase, Cmd](List(first, second)) match
        case Right(machine) =>
          assertTrue(MachineGraph.destLeaf(machine, Draft, Archive).contains("Live"))
        case Left(defect) =>
          assertTrue(defect == AssemblyDefect.DuplicateTransition("none", "none"))
    },
    test("all[Open] via event[A] beside all[Open] via event[B] compiles") {
      val result = typeCheck("""
        import mechanoid.*
        sealed trait St derives Finite
        sealed trait Open extends St
        case object D1 extends Open
        case object D2 extends Open
        case object Closed extends St
        enum Ev derives Finite:
          case A(n: String)
          case B(n: String)
        import Ev.*
        assembly[St, Ev](
          all[Open] via event[A] to Closed,
          all[Open] via event[B] to Closed,
        )
      """)
      assertZIO(result)(Assertion.isRight)
    },
    test("overlapping union aliases on different events compile") {
      val result = typeCheck("""
        import mechanoid.*
        enum St derives Finite:
          case Draft(n: String)
          case Live(n: String)
          case Gone(n: String)
        enum Ev derives Finite:
          case SetName(n: String)
          case SetWindow(n: String)
        type Renamable = St.Draft | St.Live
        type Windowed = St.Draft
        assembly[St, Ev](
          (all[Renamable] via event[Ev.SetName]).to(stay)((s, _) => s),
          (all[Windowed] via event[Ev.SetWindow]).to(stay)((s, _) => s),
        )
      """)
      assertZIO(result)(Assertion.isRight)
    },
    test("all[Renamable] via event[SetName] plus state[Draft] via event[SetName] is a duplicate") {
      val result = typeCheck("""
        import mechanoid.*
        enum St derives Finite:
          case Draft(n: String)
          case Live(n: String)
          case Gone(n: String)
        enum Ev derives Finite:
          case SetName(n: String)
          case SetWindow(n: String)
        type Renamable = St.Draft | St.Live
        assembly[St, Ev](
          (all[Renamable] via event[Ev.SetName]).to(stay)((s, _) => s),
          (state[St.Draft] via event[Ev.SetName]).to[St.Gone]((_, e) => St.Gone(e.n)),
        )
      """)
      assertZIO(result)(
        Assertion.isLeft(
          Assertion.containsString("Draft") &&
            Assertion.containsString("SetName") &&
            Assertion.not(Assertion.containsString("<init>")) &&
            Assertion.not(Assertion.containsString("?"))
        )
      )
    },
    test("the same pair with overriding compiles") {
      val result = typeCheck("""
        import mechanoid.*
        enum St derives Finite:
          case Draft(n: String)
          case Live(n: String)
          case Gone(n: String)
        enum Ev derives Finite:
          case SetName(n: String)
          case SetWindow(n: String)
        type Renamable = St.Draft | St.Live
        assembly[St, Ev](
          (all[Renamable] via event[Ev.SetName]).to(stay)((s, _) => s),
          ((state[St.Draft] via event[Ev.SetName]).to[St.Gone]((_, e) => St.Gone(e.n))) @@ Aspect.overriding,
        )
      """)
      assertZIO(result)(Assertion.isRight)
    },
    test("all viaAll plus the same leaf and event is a duplicate") {
      val result = typeCheck("""
        import mechanoid.*
        sealed trait St derives Finite
        sealed trait Open extends St
        case object D1 extends Open
        case object D2 extends Open
        case object Closed extends St
        enum Ev derives Finite:
          case Go
        import Ev.*
        type GoOnly = Go.type
        assembly[St, Ev](
          all[Open] viaAll all[GoOnly] to Closed,
          state[D1.type] via event[Go.type] to Closed,
        )
      """)
      assertZIO(result)(
        Assertion.isLeft(
          Assertion.containsString("D1") &&
            Assertion.containsString("Go") &&
            Assertion.not(Assertion.containsString("<init>")) &&
            Assertion.not(Assertion.containsString("?"))
        )
      )
    },
    test("all viaAll plus state viaAll on the same leaves is a duplicate") {
      val result = typeCheck("""
        import mechanoid.*
        sealed trait St derives Finite
        sealed trait Open extends St
        case object D1 extends Open
        case object D2 extends Open
        case object Closed extends St
        enum Ev derives Finite:
          case Go
        import Ev.*
        type GoOnly = Go.type
        assembly[St, Ev](
          all[Open] viaAll all[GoOnly] to Closed,
          state[D1.type] viaAll all[GoOnly] to Closed,
        )
      """)
      assertZIO(result)(Assertion.isLeft(Assertion.containsString("D1") && Assertion.containsString("Go")))
    },
    test("viaAnyOf plus a leaf of that event set is a duplicate") {
      val result = typeCheck("""
        import mechanoid.*
        sealed trait St derives Finite
        sealed trait Open extends St
        case object D1 extends Open
        case object D2 extends Open
        case object Closed extends St
        enum Ev derives Finite:
          case Go
          case Halt
        import St.*
        import Ev.*
        assembly[St, Ev](
          all[Open] viaAnyOf anyOfEvents(Go, Halt) to Closed,
          state[D1.type] via Go to Closed,
        )
      """)
      assertZIO(result)(Assertion.isLeft(Assertion.containsString("D1") && Assertion.containsString("Go")))
    },
    test("overlapping aliases register each group's pairs") {
      val machine = Machine(overlapAsm)
      val drift   = agrees("overlap", overlapAsm, machine)
      assertTrue(
        drift.isEmpty,
        MachineGraph.hasEdge(machine, DocDraft("a"), Resize("b")),
        !MachineGraph.hasEdge(machine, DocLive("a"), Resize("b")),
        MachineGraph.hasEdge(machine, DocDraft("a"), Rename("b")),
        MachineGraph.hasEdge(machine, DocLive("a"), Rename("b")),
      )
    },
    test("an override keeps the later leaf and the other leaves of the group") {
      val machine = Machine(overriddenAsm)
      assertTrue(
        agrees("override", overriddenAsm, machine).isEmpty,
        MachineGraph.destLeaf(machine, DocDraft("a"), Rename("z")).contains("Gone"),
        MachineGraph.destLeaf(machine, DocLive("a"), Rename("z")).contains("Live"),
      )
    },
    test("fromSpecs rejects a non-override repeat and keeps the last write otherwise") {
      enum Bit derives Finite:
        case No, Yes
      enum Tick derives Finite:
        case Up, Down
      final case class Write(from: Bit, event: Tick, to: Bit, overrideLater: Boolean)
      val bits                                               = summon[Finite[Bit]]
      val ticks                                              = summon[Finite[Tick]]
      def edge(write: Write): TransitionSpec[Bit, Tick, Bit] =
        val built = TransitionSpec.goto[Bit, Tick, Bit](
          Set(bits.caseHash(write.from)),
          Set(ticks.caseHash(write.event)),
          List(bits.nameOf(write.from)),
          List(ticks.nameOf(write.event)),
          write.to,
        )
        if write.overrideLater then built.copy(isOverride = true) else built
      val genWrite =
        for
          from          <- Gen.elements(Bit.No, Bit.Yes)
          event         <- Gen.elements(Tick.Up, Tick.Down)
          to            <- Gen.elements(Bit.No, Bit.Yes)
          overrideLater <- Gen.boolean
        yield Write(from, event, to, overrideLater)
      check(Gen.listOfBounded(0, 10)(genWrite)) { writes =>
        val conflict = writes
          .foldLeft((false, Set.empty[(Bit, Tick)])) { case ((bad, seen), write) =>
            val key = (write.from, write.event)
            if seen.contains(key) && !write.overrideLater then (true, seen)
            else (bad, seen + key)
          }
          ._1
        val last = writes.foldLeft(Map.empty[(Bit, Tick), Bit]) { (acc, write) =>
          acc.updated((write.from, write.event), write.to)
        }
        Machine.fromSpecs[Bit, Tick](writes.map(edge)) match
          case Left(_)        => assertTrue(conflict)
          case Right(machine) =>
            val dests = last.forall { (key, to) =>
              val (from, event) = key
              MachineGraph.destLeaf(machine, from, event).contains(bits.nameOf(to))
            }
            assertTrue(!conflict, dests)
      }
    },
  ) @@ TestAspect.sequential
end DuplicatePairLawSpec
