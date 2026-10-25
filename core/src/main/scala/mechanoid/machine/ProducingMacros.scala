package mechanoid.machine

import scala.quoted.*
import zio.ZIO

/** Macro implementations for type-safe producing effects.
  *
  * Provides compile-time validation that produced events share a common sealed ancestor (LUB) with the FSM's event
  * type.
  */
private[machine] object ProducingMacros:

  /** Collect ALL sealed ancestors of a type (walking up the hierarchy).
    *
    * For a type like E.B.A1 with hierarchy: sealed E -> sealed E.B -> E.B.A1
    *
    * Returns Set(hash(E), hash(E.B)) - all sealed types in the ancestor chain.
    */
  def getSealedAncestorHashes[T: Type](using Quotes): Set[Int] =
    import quotes.reflect.*

    val tpe = TypeRepr.of[T]
    val sym = tpe.typeSymbol

    def collectSealedAncestors(s: Symbol, acc: Set[Int]): Set[Int] =
      if !s.exists then acc
      else
        val newAcc = if s.flags.is(Flags.Sealed) then acc + s.fullName.hashCode else acc
        // Walk up: owner might be companion module, so handle that
        val parent = s.owner
        if parent.exists then
          if parent.flags.is(Flags.Module) then
            // Companion object - try its owner (the actual sealed trait/class)
            collectSealedAncestors(parent.owner, newAcc)
          else collectSealedAncestors(parent, newAcc)
        else newAcc

    collectSealedAncestors(sym, Set.empty)
  end getSealedAncestorHashes

  /** Implementation of producing macro.
    *
    * Validates E2 has sealed ancestors and stores them for assembly-time LUB validation.
    */
  def producingImpl[SourceS: Type, E: Type, TargetS: Type, E2: Type](
      spec: Expr[TransitionSpec[SourceS, E, TargetS]],
      f: Expr[(E, TargetS) => ZIO[Any, Any, E2]],
  )(using Quotes): Expr[TransitionSpec[SourceS, E, TargetS]] =
    import quotes.reflect.*

    val ancestorHashes = getSealedAncestorHashes[E2]

    if ancestorHashes.nonEmpty then
      // Store ALL ancestor hashes so assembly can check if FSM's E is among them
      val hashesExpr = Expr(ancestorHashes)
      '{
        $spec.copy(
          producingEffect = Some(ProducingEffect($f.asInstanceOf[(E, TargetS) => ZIO[Any, Any, E]])),
          producingAncestorHashes = Some($hashesExpr),
        )
      }
    else
      // No sealed ancestors found - FAIL compilation
      report.errorAndAbort(
        s"""Type ${TypeRepr.of[E2].show} is not a case of a sealed type.
           |
           |The producing effect must return a type that is a case of a sealed enum or trait.
           |This ensures compile-time validation that the produced event matches the FSM's event type.""".stripMargin,
        f.asTerm.pos,
      )
  end producingImpl

end ProducingMacros
