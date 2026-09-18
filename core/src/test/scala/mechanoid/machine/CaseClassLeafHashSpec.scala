package mechanoid.machine

import zio.test.*
import mechanoid.*

/** `state[CaseClass] via eventAlias` must hash the same strings Finite uses at send time.
  *
  * The docs ticket panel is this shape: sealed-trait case-class leaves, nested Active parent, events aliased as vals.
  */
object CaseClassLeafHashSpec extends ZIOSpecDefault:

  sealed trait Ticket derives Finite
  sealed trait Active                  extends Ticket derives Finite
  final case class Open(n: String)     extends Active
  final case class Archived(n: String) extends Ticket

  enum TicketEvent derives Finite:
    case Archive, Reopen

  import TicketEvent.*

  val ArchiveAlias: TicketEvent.Archive.type = TicketEvent.Archive

  val se = summon[Finite[Ticket]]
  val ee = summon[Finite[TicketEvent]]

  val open     = Open("t-1")
  val archived = Archived("t-1")

  def spec = suite("case-class leaf hashes")(
    test("state[Open] is Finite.caseHash of an Open instance") {
      assertTrue(
        state[Open].hash == se.caseHash(open),
        mechanoid.machine.Macros.hashForType[Open] == se.caseHash(open),
        mechanoid.machine.Macros.hashForType[Archived] == se.caseHash(archived),
        all[Active].hashes == Set(se.caseHash(open)),
      )
    },
    test("via hashes the enum case, including through a val alias") {
      assertTrue(
        mechanoid.machine.Macros.computeHashFor(Archive) == ee.caseHash(Archive),
        mechanoid.machine.Macros.computeHashFor(TicketEvent.Archive) == ee.caseHash(Archive),
        mechanoid.machine.Macros.computeHashFor(ArchiveAlias) == ee.caseHash(Archive),
      )
    },
    test("assembly with a val alias still has the Finite edge") {
      val machine = Machine(
        assembly[Ticket, TicketEvent](
          (state[Open] via ArchiveAlias).to[Archived] { (s, _) => Archived(s.n) }
        )
      )
      assertTrue(
        machine.transitions.contains((se.caseHash(open), ee.caseHash(Archive))),
        MachineGraph.hasEdge(machine, open, Archive),
      )
    },
    test("send Archive on an Open case-class instance") {
      val machine = Machine(
        assembly[Ticket, TicketEvent](
          (state[Open] via ArchiveAlias).to[Archived] { (s, _) => Archived(s.n) }
        )
      )
      machine.start(open).flatMap { fsm =>
        fsm.send(Archive) *> fsm.currentState.map(s => assertTrue(s == archived))
      }
    },
  )
end CaseClassLeafHashSpec
