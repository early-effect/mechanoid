package mechanoid.persistence

import zio.Chunk

/** Pull non-unique index keys (and optional domain clocks) out of an FSM state.
  *
  * The function constructor is the API. [[derived]] is the convenience for `@index` constructor parameters.
  * Nested-payload graphs write `apply` by hand; the macro does not walk nested products.
  */
trait IndexExtractor[-S]:
  def indexes(state: S): Chunk[IndexKey]
  def clocks(state: S): Option[IndexClocks]
  def rank(state: S): Option[Long]

object IndexExtractor:

  private object Empty extends IndexExtractor[Any]:
    def indexes(state: Any): Chunk[IndexKey]    = Chunk.empty
    def clocks(state: Any): Option[IndexClocks] = None
    def rank(state: Any): Option[Long]          = None

  def none[S]: IndexExtractor[S] = Empty

  def apply[S](
      keys: S => Chunk[IndexKey],
      clocksOf: S => Option[IndexClocks] = (_: S) => None,
      rankOf: S => Option[Long] = (_: S) => None,
  ): IndexExtractor[S] =
    new IndexExtractor[S]:
      def indexes(state: S): Chunk[IndexKey]    = keys(state)
      def clocks(state: S): Option[IndexClocks] = clocksOf(state)
      def rank(state: S): Option[Long]          = rankOf(state)

  /** Derive from `@index` / `@indexCreated` / `@indexUpdated` constructor params.
    *
    * The state type must be top-level (not nested in an object). Nested enums trip a Scala 3 outer-accessor crash in
    * the generated match.
    */
  inline def derived[S]: IndexExtractor[S] =
    mechanoid.macros.IndexExtractorMacros.derived[S]
end IndexExtractor
