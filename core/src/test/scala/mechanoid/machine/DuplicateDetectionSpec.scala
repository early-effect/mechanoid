package mechanoid.machine

import zio.test.*

object DuplicateDetectionSpec extends ZIOSpecDefault:

  import DuplicateDetection.*

  // Helper to create a TransitionKey with minimal required data
  def key(
      stateHash: Int,
      eventHash: Int,
      isOverride: Boolean = false,
      targetDesc: String = "-> B",
      stateNames: List[String] = List("A"),
      eventNames: List[String] = List("E1"),
  ): TransitionKey =
    TransitionKey(
      stateHashes = Set(stateHash),
      eventHashes = Set(eventHash),
      stateNames = stateNames,
      eventNames = eventNames,
      targetDesc = targetDesc,
      isOverride = isOverride,
      sourceDesc = s"${stateNames.mkString} via ${eventNames.mkString}",
    )

  def spec = suite("DuplicateDetectionSpec")(
    suite("detectDuplicates")(
      test("returns empty result for no duplicates") {
        val keys = List(
          (key(1, 10, targetDesc = "-> B"), 0),
          (key(2, 20, targetDesc = "-> C"), 1),
          (key(3, 30, targetDesc = "-> D"), 2),
        )
        val result = detectDuplicates(keys)
        assertTrue(
          result.error.isEmpty,
          result.overrideInfos.isEmpty,
        )
      },
      test("returns error for duplicate without override") {
        val keys = List(
          (key(1, 10, targetDesc = "-> B"), 0),
          (key(1, 10, targetDesc = "-> C"), 1), // Same state/event hash, no override
        )
        val result = detectDuplicates(keys)
        assertTrue(
          result.error.isDefined,
          result.error.get.firstIndex == 0,
          result.error.get.duplicateIndex == 1,
          result.error.get.firstTargetDesc == "-> B",
          result.error.get.targetDesc == "-> C",
        )
      },
      test("returns override info when duplicate has override marker") {
        val keys = List(
          (key(1, 10, targetDesc = "-> B"), 0),
          (key(1, 10, isOverride = true, targetDesc = "-> C"), 1),
        )
        val result = detectDuplicates(keys)
        assertTrue(
          result.error.isEmpty,
          result.overrideInfos.size == 1,
          result.overrideInfos.head.prevTarget == "B",
          result.overrideInfos.head.newTarget == "C",
          result.overrideInfos.head.firstIndex == 0,
          result.overrideInfos.head.overrideIndex == 1,
        )
      },
      test("later spec must have override - order matters") {
        // First spec has override but second doesn't - this should error
        val keys = List(
          (key(1, 10, isOverride = true, targetDesc = "-> B"), 0),
          (key(1, 10, targetDesc = "-> C"), 1), // No override on later spec
        )
        val result = detectDuplicates(keys)
        assertTrue(
          result.error.isDefined,
          result.error.get.duplicateIndex == 1,
        )
      },
      test("handles multiple state/event hash combinations") {
        // Key with multiple states and events (like anyOf)
        val multiKey = TransitionKey(
          stateHashes = Set(1, 2),
          eventHashes = Set(10, 20),
          stateNames = List("A", "B"),
          eventNames = List("E1", "E2"),
          targetDesc = "-> C",
          isOverride = false,
          sourceDesc = "A,B via E1,E2",
        )
        val overlappingKey = TransitionKey(
          stateHashes = Set(1), // Overlaps with multiKey
          eventHashes = Set(10),
          stateNames = List("A"),
          eventNames = List("E1"),
          targetDesc = "-> D",
          isOverride = true,
          sourceDesc = "A via E1",
        )
        val keys   = List((multiKey, 0), (overlappingKey, 1))
        val result = detectDuplicates(keys)
        assertTrue(
          result.error.isEmpty,
          result.overrideInfos.size == 1,
        )
      },
      test("handles three-way duplicates with proper override chain") {
        val keys = List(
          (key(1, 10, targetDesc = "-> B"), 0),
          (key(1, 10, isOverride = true, targetDesc = "-> C"), 1),
          (key(1, 10, isOverride = true, targetDesc = "-> D"), 2),
        )
        val result = detectDuplicates(keys)
        assertTrue(
          result.error.isEmpty,
          result.overrideInfos.size == 2,
        )
      },
      test("error message is properly formatted") {
        val keys = List(
          (key(1, 10, targetDesc = "-> B", stateNames = List("StateA"), eventNames = List("Event1")), 0),
          (key(1, 10, targetDesc = "-> C", stateNames = List("StateA"), eventNames = List("Event1")), 1),
        )
        val result = detectDuplicates(keys)
        assertTrue(
          result.error.isDefined,
          result.error.get.message("Duplicate transition").contains("Duplicate transition without override"),
          result.error.get.message("Duplicate transition").contains("spec #1"),
          result.error.get.message("Duplicate transition").contains("spec #2"),
          result.error.get.message("Duplicate transition").contains("@@ Aspect.overriding"),
        )
      },
    ),
    suite("findOrphanOverrides")(
      test("returns empty for no overrides") {
        val keys = List(
          (key(1, 10), 0),
          (key(2, 20), 1),
        )
        val result = findOrphanOverrides(keys)
        assertTrue(result.isEmpty)
      },
      test("finds orphan when override has no duplicate") {
        val orphanKey = key(1, 10, isOverride = true)
        val keys      = List(
          (orphanKey, 0),
          (key(2, 20), 1), // Different key, so first is orphan
        )
        val result = findOrphanOverrides(keys)
        assertTrue(
          result.size == 1,
          result.head == orphanKey,
        )
      },
      test("does not return override that has duplicate") {
        val keys = List(
          (key(1, 10), 0),
          (key(1, 10, isOverride = true), 1), // Override has duplicate, not orphan
        )
        val result = findOrphanOverrides(keys)
        assertTrue(result.isEmpty)
      },
      test("finds multiple orphans") {
        val orphan1 = key(1, 10, isOverride = true, stateNames = List("A"))
        val orphan2 = key(2, 20, isOverride = true, stateNames = List("B"))
        val keys    = List(
          (orphan1, 0),
          (orphan2, 1),
        )
        val result = findOrphanOverrides(keys)
        assertTrue(result.size == 2)
      },
      test("partial key overlap prevents orphan status") {
        // If any key pair matches, it's not an orphan
        val multiKey = TransitionKey(
          stateHashes = Set(1, 2),
          eventHashes = Set(10),
          stateNames = List("A", "B"),
          eventNames = List("E1"),
          targetDesc = "-> C",
          isOverride = true,
          sourceDesc = "A,B via E1",
        )
        val matchingKey = key(1, 10) // Matches one of multiKey's state/event pairs
        val keys        = List((matchingKey, 0), (multiKey, 1))
        val result      = findOrphanOverrides(keys)
        assertTrue(result.isEmpty) // multiKey is not orphan because (1, 10) appears twice
      },
    ),
    suite("sourceDescFromNames")(
      test("formats names correctly") {
        val desc = sourceDescFromNames(List("StateA", "StateB"), List("Event1"), 0)
        assertTrue(desc == "StateA,StateB via Event1")
      },
      test("falls back to index when names empty") {
        val desc = sourceDescFromNames(Nil, Nil, 5)
        assertTrue(desc == "spec #6")
      },
      test("falls back when only state names empty") {
        val desc = sourceDescFromNames(Nil, List("E1"), 3)
        assertTrue(desc == "spec #4")
      },
      test("falls back when only event names empty") {
        val desc = sourceDescFromNames(List("A"), Nil, 2)
        assertTrue(desc == "spec #3")
      },
    ),
    suite("TransitionKey.transitionDesc")(
      test("returns formatted desc when both names present") {
        val tk = TransitionKey(
          stateHashes = Set(1),
          eventHashes = Set(2),
          stateNames = List("A", "B"),
          eventNames = List("E1", "E2"),
          targetDesc = "-> C",
          isOverride = false,
          sourceDesc = "fallback",
        )
        assertTrue(tk.transitionDesc == "A,B via E1,E2")
      },
      test("falls back to sourceDesc when stateNames empty") {
        val tk = TransitionKey(
          stateHashes = Set(1),
          eventHashes = Set(2),
          stateNames = Nil,
          eventNames = List("E1"),
          targetDesc = "-> C",
          isOverride = false,
          sourceDesc = "fallback description",
        )
        assertTrue(tk.transitionDesc == "fallback description")
      },
      test("falls back to sourceDesc when eventNames empty") {
        val tk = TransitionKey(
          stateHashes = Set(1),
          eventHashes = Set(2),
          stateNames = List("A"),
          eventNames = Nil,
          targetDesc = "-> C",
          isOverride = false,
          sourceDesc = "fallback description",
        )
        assertTrue(tk.transitionDesc == "fallback description")
      },
      test("falls back to sourceDesc when both names empty") {
        val tk = TransitionKey(
          stateHashes = Set(1),
          eventHashes = Set(2),
          stateNames = Nil,
          eventNames = Nil,
          targetDesc = "-> C",
          isOverride = false,
          sourceDesc = "complete fallback",
        )
        assertTrue(tk.transitionDesc == "complete fallback")
      },
    ),
  )
end DuplicateDetectionSpec
