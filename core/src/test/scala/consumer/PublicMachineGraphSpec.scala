package consumer

import mechanoid.*
import zio.test.*

/** Lives outside `package mechanoid` so it cannot see `private[mechanoid]` fields. */
object PublicMachineGraphSpec extends ZIOSpecDefault:

  enum S derives Finite:
    case A, B

  enum E derives Finite:
    case Go, Nope

  import S.*, E.*

  val machine: Machine[S, E] = Machine(
    assembly[S, E](A via Go to B)
  )

  def spec = suite("PublicMachineGraphSpec")(
    test("hasEdge and destLeaf are usable from a consumer package") {
      assertTrue(
        MachineGraph.hasEdge(machine, A, Go),
        MachineGraph.destLeaf(machine, A, Go).contains("B"),
        !MachineGraph.hasEdge(machine, A, Nope),
        MachineGraph.destLeaf(machine, A, Nope).isEmpty,
      )
    }
  )
end PublicMachineGraphSpec
