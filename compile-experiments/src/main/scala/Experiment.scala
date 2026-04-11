package experiments

import mechanoid.machine.*
import mechanoid.core.Finite

/** Quick iteration sandbox for macro debugging.
  *
  * Use `sbt compileExperiments/compile` to test new patterns.
  *
  * Note: Compile-time validation tests are now in the `compile-time-checks` module, which runs them as proper tests
  * with `-Werror` enabled to catch warnings.
  */
object Experiment:

  enum TestState derives Finite:
    case A, B, C

  enum TestEvent derives Finite:
    case E1, E2, E3

  import TestState.*
  import TestEvent.*

  // Basic assembly
  val simple = assembly[TestState, TestEvent](
    A via E1 to B,
    B via E2 to C,
  )

  // Assembly with override via composition (resolves duplicate - no warning)
  val withOverride = Machine(
    assembly[TestState, TestEvent](A via E1 to B) ++
      assembly[TestState, TestEvent]((A via E1 to C) @@ Aspect.overriding)
  )

  // Combined assemblies
  val combined = assembly[TestState, TestEvent](A via E1 to B) ++
    assembly[TestState, TestEvent](B via E2 to C)

  // Machine from assembly
  val machine = Machine(
    assembly[TestState, TestEvent](
      A via E1 to B,
      B via E2 to C,
    )
  )

end Experiment
