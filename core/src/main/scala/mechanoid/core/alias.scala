package mechanoid.core

import scala.annotation.StaticAnnotation

/** Marks a state constructor parameter as a unique alias for [[InstanceIndex]].
  *
  * The optional `namespace` is the alias namespace. When omitted (or empty), the field name is used. Scalar fields
  * become one alias; `Option` / `Iterable` / `Chunk` / `List` / `Seq` fields become one alias per element. Values are
  * encoded with [[AliasCodec]] (`toString` by default).
  *
  * {{{
  * enum InitiativeState derives Finite:
  *   case Draft
  *   case Live(
  *     @alias("campaign") campaignIds: List[Long],
  *     @alias templateId: String, // namespace "templateId"
  *   )
  * }}}
  */
final class alias(val namespace: String = "") extends StaticAnnotation
