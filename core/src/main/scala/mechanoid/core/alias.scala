package mechanoid.core

import scala.annotation.StaticAnnotation

/** Marks a state constructor parameter as a unique alias. The namespace is the field name.
  *
  * {{{
  * enum InitiativeState derives Finite:
  *   case Live(@alias campaign: List[Long], @alias templateId: String)
  * }}}
  */
final class alias() extends StaticAnnotation
