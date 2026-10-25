package mechanoid.machine

/** Pure duplicate and orphan detection logic.
  *
  * This module contains the core algorithms for detecting duplicate transitions and orphan overrides. All functions are
  * pure and don't require macro context, making them unit testable.
  */
private[machine] object DuplicateDetection:

  /** Input data for duplicate detection - mirrors SpecHashInfo but without macro dependencies. */
  case class TransitionKey(
      stateHashes: Set[Int],
      eventHashes: Set[Int],
      stateNames: List[String],
      eventNames: List[String],
      targetDesc: String,
      isOverride: Boolean,
      sourceDesc: String,
  ):
    /** Human-readable description for error messages. */
    def transitionDesc: String =
      if stateNames.nonEmpty && eventNames.nonEmpty then s"${stateNames.mkString(",")} via ${eventNames.mkString(",")}"
      else sourceDesc

  /** Error representing a duplicate transition without override marker. */
  case class DuplicateError(
      transitionDesc: String,
      targetDesc: String,
      firstIndex: Int,
      firstTargetDesc: String,
      duplicateIndex: Int,
  ):
    def message(errorPrefix: String): String =
      s"""$errorPrefix without override!
         |  Transition: $transitionDesc $targetDesc
         |  First defined at spec #${firstIndex + 1}: $firstTargetDesc
         |  Duplicate at spec #${duplicateIndex + 1}: $targetDesc
         |
         |  To override, use: (...) @@ Aspect.overriding""".stripMargin

  /** Info about a resolved override (for informational messages). */
  case class OverrideInfo(
      transitionDesc: String,
      prevTarget: String,
      newTarget: String,
      firstIndex: Int,
      overrideIndex: Int,
  ):
    def message: String =
      s"  $transitionDesc: $prevTarget (spec #${firstIndex + 1}) -> $newTarget (spec #${overrideIndex + 1})"

  /** Result of duplicate detection. */
  case class DuplicateCheckResult(
      error: Option[DuplicateError],
      overrideInfos: List[OverrideInfo],
  )

  /** Build source description from transition key data.
    *
    * @param stateNames
    *   State names from the transition
    * @param eventNames
    *   Event names from the transition
    * @param fallbackIdx
    *   Index to use in fallback description
    * @return
    *   Human-readable source description
    */
  def sourceDescFromNames(stateNames: List[String], eventNames: List[String], fallbackIdx: Int): String =
    if stateNames.nonEmpty && eventNames.nonEmpty then s"${stateNames.mkString(",")} via ${eventNames.mkString(",")}"
    else s"spec #${fallbackIdx + 1}"

  /** Detect duplicate transitions in a list of specs.
    *
    * This is the core duplicate detection algorithm. It builds a registry of (stateHash, eventHash) -> specs and checks
    * for duplicates. If a duplicate is found without an override marker on the later spec, it returns an error.
    *
    * @param keys
    *   List of (TransitionKey, index) pairs in source order
    * @return
    *   DuplicateCheckResult with either an error or override info
    */
  def detectDuplicates(keys: List[(TransitionKey, Int)]): DuplicateCheckResult =
    // Build registry: (stateHash, eventHash) -> List[(TransitionKey, index)]
    val registry = scala.collection.mutable.Map[(Int, Int), List[(TransitionKey, Int)]]()

    for (key, idx) <- keys do
      for
        stateHash <- key.stateHashes
        eventHash <- key.eventHashes
      do
        val mapKey = (stateHash, eventHash)
        registry(mapKey) = registry.getOrElse(mapKey, Nil) :+ (key, idx)

    // Check for duplicates - collect errors and override infos
    val duplicatesWithMultiple = registry.filter(_._2.size > 1).values.toList

    // Process each group of duplicates and collect results
    val results: List[Either[DuplicateError, List[OverrideInfo]]] = duplicatesWithMultiple.map { specList =>
      val (first, firstIdx) = specList.head
      val tailResults       = specList.tail.map { case (key, idx) =>
        if !key.isOverride then
          Left(
            DuplicateError(
              transitionDesc = key.transitionDesc,
              targetDesc = key.targetDesc,
              firstIndex = firstIdx,
              firstTargetDesc = first.targetDesc,
              duplicateIndex = idx,
            )
          )
        else
          val prevTarget = first.targetDesc.stripPrefix("-> ")
          val newTarget  = key.targetDesc.stripPrefix("-> ")
          Right(
            OverrideInfo(
              transitionDesc = key.transitionDesc,
              prevTarget = prevTarget,
              newTarget = newTarget,
              firstIndex = firstIdx,
              overrideIndex = idx,
            )
          )
      }
      // If any error, return it; otherwise collect all override infos
      tailResults.collectFirst { case Left(err) => err } match
        case Some(err) => Left(err)
        case None      => Right(tailResults.collect { case Right(info) => info })
    }

    // Check for first error
    results.collectFirst { case Left(err) => err } match
      case Some(err) => DuplicateCheckResult(error = Some(err), overrideInfos = Nil)
      case None      =>
        val allInfos = results.collect { case Right(infos) => infos }.flatten
        DuplicateCheckResult(error = None, overrideInfos = allInfos)
  end detectDuplicates

  /** Find orphan overrides in a list of specs.
    *
    * An orphan override is a spec marked with `@@ Aspect.overriding` that doesn't actually override anything (all its
    * keys appear only once).
    *
    * @param keys
    *   List of (TransitionKey, index) pairs
    * @return
    *   List of TransitionKey that are orphan overrides
    */
  def findOrphanOverrides(keys: List[(TransitionKey, Int)]): List[TransitionKey] =
    // Count occurrences of each (stateHash, eventHash) pair
    val allKeyPairs = keys.flatMap { case (key, _) =>
      for s <- key.stateHashes; e <- key.eventHashes yield (s, e)
    }
    val keyCounts = allKeyPairs.groupBy(identity).view.mapValues(_.size).toMap

    // Find overrides where ALL keys have count == 1 (no duplicate to override)
    keys.collect {
      case (key, _) if key.isOverride =>
        val specKeys = for s <- key.stateHashes; e <- key.eventHashes yield (s, e)
        val isOrphan = specKeys.forall(k => keyCounts.getOrElse(k, 0) == 1)
        if isOrphan then Some(key) else None
    }.flatten
  end findOrphanOverrides

end DuplicateDetection
