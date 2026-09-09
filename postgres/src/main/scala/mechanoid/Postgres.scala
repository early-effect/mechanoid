package mechanoid

import zio.json.*
import scala.annotation.nowarn

/** Re-export PostgreSQL implementations for simple imports.
  *
  * This allows users to write:
  * {{{
  * import mechanoid.*
  * import mechanoid.postgres.{*, given}
  * }}}
  *
  * Instead of the more verbose:
  * {{{
  * import mechanoid.persistence.postgres.*
  * }}}
  *
  * The postgres package also provides automatic `JsonCodec` derivation for any type with `Finite`. This means users
  * don't need to explicitly derive `JsonCodec` for their state and event types - it's handled automatically when using
  * PostgreSQL persistence via the `finiteJsonCodec` given.
  */
object postgres:
  export persistence.postgres.PostgresEventStore
  export persistence.postgres.PostgresTimeoutStore
  export persistence.postgres.PostgresInstanceLock
  export persistence.postgres.PostgresLeaseStore
  export persistence.postgres.PostgresInstanceIndex
  export persistence.postgres.PostgresSchema

  /** Auto-derive JsonCodec for any type with Finite.
    *
    * This is only needed for PostgreSQL persistence (JSON serialization). Users need to import this with
    * `import mechanoid.postgres.{*, given}` or `import mechanoid.postgres.given`.
    *
    * The inline Mirror parameter allows derivation at the use site.
    */
  @nowarn("msg=unused implicit parameter")
  transparent inline given finiteJsonCodec[T](using
      inline f: core.Finite[T],
      inline m: scala.deriving.Mirror.Of[T],
  ): JsonCodec[T] =
    JsonCodec.derived[T]
end postgres
