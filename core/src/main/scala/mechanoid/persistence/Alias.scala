package mechanoid.persistence

import scala.annotation.unused
import mechanoid.core.Finite

/** A unique secondary key that resolves to one FSM instance.
  *
  * Construct at call sites with [[Alias.of]]: `Alias.of[InitiativeState].campaign(id)`.
  */
final case class Alias(namespace: String, key: String):
  override def toString: String = s"$namespace/$key"

object Alias:
  transparent inline def of[S](using @unused ev: Finite[S]) =
    mechanoid.macros.NsMacros.aliasOf[S]
