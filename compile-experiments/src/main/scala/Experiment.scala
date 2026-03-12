package experiments

import mechanoid.machine.*
import mechanoid.core.Finite

/** Test cases for compile-time assembly validation.
  *
  * This module exists for quick iteration on macro debugging. Use `sbt compileExperiments/compile` to test.
  */
object Experiment:

  enum TestState derives Finite:
    case A, B, C

  enum TestEvent derives Finite:
    case E1, E2, E3

  import TestState.*
  import TestEvent.*

  // Test case 1: combine with override
  val combinedWithOverride = combine(
    assembly[TestState, TestEvent](A via E1 to B),
    assembly[TestState, TestEvent]((A via E1 to C) @@ Aspect.overriding),
  )

  // Test case 2: ++ with no duplicates
  val combinedPlusPlus = assembly[TestState, TestEvent](A via E1 to B) ++
    assembly[TestState, TestEvent](B via E2 to C)

  // Test case 3: Orphan override via inline def - should emit warning
  inline def orphanAssemblyInline = assembly[TestState, TestEvent](
    (A via E1 to B) @@ Aspect.overriding // No duplicate - orphan!
  )
  val machineWithOrphanInline = Machine(orphanAssemblyInline)

  // Test case 4: Inline orphan override - should emit compile-time warning
  val machineWithInlineOrphan = Machine(
    assembly[TestState, TestEvent](
      (A via E1 to B) @@ Aspect.overriding // No duplicate - orphan!
    )
  )

  // Test case 5: Non-orphan override (composed via ++) - should NOT warn
  val machineComposed = Machine(
    assembly[TestState, TestEvent](A via E1 to B) ++
      assembly[TestState, TestEvent]((A via E1 to C) @@ Aspect.overriding)
  )

  // Test case 6: Inline composed assembly - should NOT warn (orphan resolved)
  val machineInlineComposed = Machine(
    assembly[TestState, TestEvent](A via E2 to B) ++
      assembly[TestState, TestEvent]((A via E2 to C) @@ Aspect.overriding)
  )

  // ========================================================================
  // Compile-time duplicate rejection tests
  // Uncomment one at a time to verify compile error, then re-comment.
  // ========================================================================

  // EXPECTED COMPILE ERROR: direct duplicate without override in assembly()
  // val dupDirect = assembly[TestState, TestEvent](
  //   A via E1 to B,
  //   A via E1 to C,
  // )

  // EXPECTED COMPILE ERROR: duplicate in assemblyAll block syntax
  // val dupAll = assemblyAll[TestState, TestEvent]:
  //   A via E1 to B
  //   A via E1 to C

  // EXPECTED COMPILE ERROR: combine with non-overridden duplicate
  // val combinedDup = combine(
  //   assembly[TestState, TestEvent](A via E3 to B),
  //   assembly[TestState, TestEvent](A via E3 to C),
  // )

  // EXPECTED COMPILE ERROR: ++ with non-overridden duplicate
  // val combinedPlusPlusDup = assembly[TestState, TestEvent](A via E3 to B) ++
  //   assembly[TestState, TestEvent](A via E3 to C)

  // ========================================================================
  // combine() combinator tests
  // ========================================================================

  // Test: combine with no duplicates (should compile)
  val combinedNoDup = combine(
    assembly[TestState, TestEvent](A via E3 to B),
    assembly[TestState, TestEvent](B via E3 to C),
  )

  // Test: combine with override (should compile)
  val combinedOverride = combine(
    assembly[TestState, TestEvent](A via E3 to B),
    assembly[TestState, TestEvent]((A via E3 to C) @@ Aspect.overriding),
  )

  // Test: Machine(a ++ b) - compose then wrap in Machine
  val machineFromCombined = Machine(
    assembly[TestState, TestEvent](A via E1 to B) ++
      assembly[TestState, TestEvent](B via E2 to C)
  )

  // Test: Machine(combine(a, b)) - same via combine
  val machineFromCombine = Machine(
    combine(
      assembly[TestState, TestEvent](A via E1 to B),
      assembly[TestState, TestEvent](B via E2 to C),
    )
  )

  // EXPECTED COMPILE ERROR: duplicate through Machine(assembly(...))
  // val dupMachine = Machine(assembly[TestState, TestEvent](
  //   A via E1 to B,
  //   A via E1 to C,
  // ))

end Experiment
