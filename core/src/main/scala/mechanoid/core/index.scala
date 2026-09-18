package mechanoid.core

import scala.annotation.StaticAnnotation

/** Marks a state constructor parameter as a non-unique index key. The namespace is the field name. */
final class index() extends StaticAnnotation

/** Marks an `Instant` constructor parameter as the domain created clock (`created_at`). */
final class indexCreated() extends StaticAnnotation

/** Marks an `Instant` constructor parameter as the domain edited clock (`edited_at`). */
final class indexUpdated() extends StaticAnnotation

/** Marks an `Int` / `Long` / `Short` constructor parameter as covering rank. */
final class indexRank() extends StaticAnnotation
