package mechanoid.persistence

import zio.Chunk

/** Pull unique aliases out of an FSM state so the runtime can keep [[InstanceIndex]] in sync.
  *
  * Optional. Callers who bind explicitly via [[InstanceIndex.bindAll]] do not need this.
  *
  * The runtime diffs `aliases(old)` vs `aliases(new)` as sets: added keys are bound before the event is appended;
  * removed keys are unbound after. An empty diff does not touch the index, so a stable list of 100 campaign ids is free
  * on later transitions.
  *
  * {{{
  * val extractor: AliasExtractor[InitiativeState] =
  *   state =>
  *     Chunk.fromIterable(state.campaignIds.map(id => Alias("campaign", id))) ++
  *       Chunk.fromIterable(state.templateIds.map(id => Alias("template", id)))
  * }}}
  */
trait AliasExtractor[-S]:
  def aliases(state: S): Chunk[Alias]

object AliasExtractor:

  private object Empty extends AliasExtractor[Any]:
    def aliases(state: Any): Chunk[Alias] = Chunk.empty

  def none[S]: AliasExtractor[S] = Empty

  def apply[S](f: S => Chunk[Alias]): AliasExtractor[S] =
    (state: S) => f(state)

  /** Derive from `@alias` constructor parameters on `S` (enum / sealed trait / case class).
    *
    * States with no annotated fields yield an empty extractor (same as [[none]]).
    */
  inline def derived[S]: AliasExtractor[S] =
    mechanoid.macros.AliasExtractorMacros.derived[S]
end AliasExtractor
