package mechanoid.machine

import zio.test.*
import mechanoid.core.Finite

object AssemblySpec extends ZIOSpecDefault:

  // Test state and event types
  enum TestState derives Finite:
    case A, B, C

  enum TestEvent derives Finite:
    case E1, E2, E3

  import TestState.*
  import TestEvent.*

  def spec = suite("AssemblySpec")(
    suite("assembly creation")(
      test("creates assembly from single spec") {
        val asm = assembly[TestState, TestEvent](A via E1 to B)
        assertTrue(asm.specs.size == 1)
      },
      test("creates assembly from multiple specs") {
        val asm = assembly[TestState, TestEvent](
          A via E1 to B,
          B via E2 to C,
        )
        assertTrue(asm.specs.size == 2)
      },
      test("preserves spec order") {
        val asm = assembly[TestState, TestEvent](
          A via E1 to B,
          B via E2 to C,
          C via E3 to A,
        )
        assertTrue(
          asm.specs.size == 3,
          asm.specs(0).stateNames.head == "A",
          asm.specs(1).stateNames.head == "B",
          asm.specs(2).stateNames.head == "C",
        )
      },
      test("allows duplicate with @@ Aspect.overriding") {
        val asm = assembly[TestState, TestEvent](
          A via E1 to B,
          (A via E1 to C) @@ Aspect.overriding,
        )
        // Should compile without error, last one wins
        assertTrue(asm.specs.size == 2)
      },
    ),
    suite("assemblyAll block syntax")(
      test("creates assembly from block without commas") {
        val asm = assemblyAll[TestState, TestEvent]:
          A via E1 to B
          B via E2 to C
        assertTrue(asm.specs.size == 2)
      },
      test("supports multiple transitions") {
        val asm = assemblyAll[TestState, TestEvent]:
          A via E1 to B
          B via E2 to C
          C via E3 to A
        assertTrue(asm.specs.size == 3)
      },
    ),
    suite("combine/++ composition")(
      test("combine flattens assembly specs") {
        val combined = combine(
          assembly[TestState, TestEvent](A via E1 to B),
          assembly[TestState, TestEvent](B via E2 to C),
        )
        assertTrue(combined.specs.size == 2)
      },
      test("++ flattens assembly specs") {
        val combined = assembly[TestState, TestEvent](A via E1 to B) ++
          assembly[TestState, TestEvent](B via E2 to C)
        assertTrue(combined.specs.size == 2)
      },
      test("combine with override") {
        val combined = combine(
          assembly[TestState, TestEvent](A via E1 to B),
          assembly[TestState, TestEvent]((A via E1 to C) @@ Aspect.overriding),
        )
        assertTrue(combined.specs.size == 2)
      },
      test("++ with override") {
        val combined = assembly[TestState, TestEvent](A via E1 to B) ++
          assembly[TestState, TestEvent]((A via E1 to C) @@ Aspect.overriding)
        assertTrue(combined.specs.size == 2)
      },
      test("combine three assemblies via chaining") {
        val combined = combine(
          assembly[TestState, TestEvent](A via E1 to B),
          assembly[TestState, TestEvent](B via E2 to C),
        ) ++ assembly[TestState, TestEvent](C via E3 to A)
        assertTrue(combined.specs.size == 3)
      },
    ),
    suite("hierarchical matching")(
      test("all[Parent] matches all children") {
        // Create a sealed hierarchy
        sealed trait ParentState derives Finite
        case object Child1 extends ParentState
        case object Child2 extends ParentState
        case object Target extends ParentState

        enum SimpleEvent derives Finite:
          case Reset

        val asm = assembly[ParentState, SimpleEvent](
          all[ParentState] via SimpleEvent.Reset to Target
        )
        // all[ParentState] should expand to match Child1, Child2, Target
        assertTrue(asm.specs.nonEmpty)
      },
      test("anyOf() matches specific states") {
        val asm = assembly[TestState, TestEvent](
          anyOf(A, B) via E1 to C
        )
        // Should have specs for both A and B
        assertTrue(asm.specs.head.stateHashes.size == 2)
      },
      test("anyOfEvents() matches specific events") {
        val asm = assembly[TestState, TestEvent](
          A viaAnyOf anyOfEvents(E1, E2) to B
        )
        assertTrue(asm.specs.head.eventHashes.size == 2)
      },
    ),
    suite("DSL syntax")(
      test("state via event to target") {
        val asm = assembly[TestState, TestEvent](A via E1 to B)
        assertTrue(asm.specs.size == 1)
      },
      test("stop terminal") {
        val asm = assembly[TestState, TestEvent](A via E1 to stop("done"))
        assertTrue(asm.specs.head.handler.isInstanceOf[Handler.Stop])
      },
      test("stay terminal") {
        val asm = assembly[TestState, TestEvent](A via E1 to stay)
        assertTrue(asm.specs.head.handler == Handler.Stay)
      },
    ),
    suite("val-based specs")(
      test("single spec via val") {
        val t1  = A via E1 to B
        val asm = assembly[TestState, TestEvent](t1)
        assertTrue(asm.specs.size == 1)
      },
      test("multiple specs via vals") {
        val t1  = A via E1 to B
        val t2  = B via E2 to C
        val asm = assembly[TestState, TestEvent](t1, t2)
        assertTrue(asm.specs.size == 2)
      },
      test("mix of inline and val specs") {
        val t1  = A via E1 to B
        val asm = assembly[TestState, TestEvent](
          t1,
          B via E2 to C,
        )
        assertTrue(asm.specs.size == 2)
      },
      test("val spec with override at call site") {
        val t1  = A via E1 to B
        val asm = assembly[TestState, TestEvent](
          A via E1 to C,
          t1 @@ Aspect.overriding,
        )
        assertTrue(asm.specs.size == 2)
      },
      test("val spec with override in definition") {
        val t1  = (A via E1 to B) @@ Aspect.overriding
        val asm = assembly[TestState, TestEvent](
          A via E1 to C,
          t1,
        )
        assertTrue(asm.specs.size == 2)
      },
    ),
    suite("Assembly.apply factory")(
      test("creates assembly with all parameters") {
        import zio.ZIO
        val specs     = List.empty[TransitionSpec[TestState, TestEvent, ?]]
        val hashInfos = List.empty[IncludedHashInfo]
        val orphans   = Set.empty[OrphanInfo]
        val entryEffects: Map[Int, (TestEvent, TestState) => ZIO[Any, Any, Unit]] = Map.empty
        val exitEffects: Map[Int, (TestEvent, TestState) => ZIO[Any, Any, Unit]]  = Map.empty
        val asm = Assembly(specs, hashInfos, orphans, entryEffects, exitEffects)
        assertTrue(
          asm.specs.isEmpty,
          asm.hashInfos.isEmpty,
          asm.orphanOverrides.isEmpty,
          asm.stateEntryEffects.isEmpty,
          asm.stateExitEffects.isEmpty,
        )
      },
      test("creates assembly with default parameters") {
        val specs     = List.empty[TransitionSpec[TestState, TestEvent, ?]]
        val hashInfos = List.empty[IncludedHashInfo]
        // Call Assembly constructor with only required params to exercise default parameter branches
        val asm = Assembly(specs, hashInfos)
        assertTrue(
          asm.specs.isEmpty,
          asm.orphanOverrides == Set.empty,   // Uses default Set.empty
          asm.stateEntryEffects == Map.empty, // Uses default Map.empty
          asm.stateExitEffects == Map.empty,  // Uses default Map.empty
        )
      },
    ),
    suite("onEnter and onExit effects")(
      test("onEnter adds state entry effect") {
        import zio.ZIO
        val asm = assembly[TestState, TestEvent](A via E1 to B)
          .onEnter(B) { (_, _) => ZIO.unit }
        assertTrue(asm.stateEntryEffects.nonEmpty)
      },
      test("onExit adds state exit effect") {
        import zio.ZIO
        val asm = assembly[TestState, TestEvent](A via E1 to B)
          .onExit(A) { (_, _) => ZIO.unit }
        assertTrue(asm.stateExitEffects.nonEmpty)
      },
      test("multiple onEnter effects for different states") {
        import zio.ZIO
        val asm = assembly[TestState, TestEvent](
          A via E1 to B,
          B via E2 to C,
        ).onEnter(B) { (_, _) => ZIO.unit }
          .onEnter(C) { (_, _) => ZIO.unit }
        assertTrue(asm.stateEntryEffects.size == 2)
      },
      test("multiple onExit effects for different states") {
        import zio.ZIO
        val asm = assembly[TestState, TestEvent](
          A via E1 to B,
          B via E2 to C,
        ).onExit(A) { (_, _) => ZIO.unit }
          .onExit(B) { (_, _) => ZIO.unit }
        assertTrue(asm.stateExitEffects.size == 2)
      },
      test("onEnter overwrites existing effect for same state") {
        import zio.ZIO
        val asm = assembly[TestState, TestEvent](A via E1 to B)
          .onEnter(B) { (_, _) => ZIO.unit }
          .onEnter(B) { (_, _) => ZIO.unit }
        assertTrue(asm.stateEntryEffects.size == 1)
      },
    ),
    suite("IncludedHashInfo")(
      test("stores all fields correctly") {
        val info = IncludedHashInfo(
          stateHashes = Set(1, 2),
          eventHashes = Set(3),
          stateNames = List("A", "B"),
          eventNames = List("E1"),
          targetDesc = "-> C",
          isOverride = true,
        )
        assertTrue(
          info.stateHashes == Set(1, 2),
          info.eventHashes == Set(3),
          info.stateNames == List("A", "B"),
          info.eventNames == List("E1"),
          info.targetDesc == "-> C",
          info.isOverride,
        )
      }
    ),
    suite("OrphanInfo")(
      test("description formats correctly for single state/event") {
        val info = OrphanInfo(
          stateHashes = Set(1),
          eventHashes = Set(2),
          stateNames = List("A"),
          eventNames = List("E1"),
        )
        assertTrue(info.description == "A via E1")
      },
      test("description formats correctly for multiple states/events") {
        val info = OrphanInfo(
          stateHashes = Set(1, 2),
          eventHashes = Set(3, 4),
          stateNames = List("A", "B"),
          eventNames = List("E1", "E2"),
        )
        assertTrue(info.description == "A,B via E1,E2")
      },
    ),
  )

end AssemblySpec
