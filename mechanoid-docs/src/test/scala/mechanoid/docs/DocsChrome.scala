package mechanoid.docs

import earlyeffect.docs.EarlyEffectTheme
import specular.site.{DocsSite, SiteBuilder, Theme}
import zio.ZLayer

/** Live-demo chrome for Mechanoid DocSpecs. Diagram paint comes from `Mermoid.chalkboard`. */
object DocsChrome:

  private val liveCss: String =
    """
      |.mechanoid-multitab-demo {
      |  display: flex;
      |  flex-direction: column;
      |  gap: 0.85rem;
      |}
      |.mechanoid-live-fsm {
      |  border-radius: 8px;
      |  padding: 0.75rem 0.5rem 1rem;
      |  background: #1c1d1f;
      |}
      |.mechanoid-live-status {
      |  font-size: 0.95rem;
      |  opacity: 0.92;
      |}
      |.mechanoid-live-actions {
      |  display: flex;
      |  gap: 0.6rem;
      |  flex-wrap: wrap;
      |}
      |.mechanoid-live-actions button {
      |  font: inherit;
      |  padding: 0.4rem 0.9rem;
      |  cursor: pointer;
      |  border-radius: 4px;
      |  border: 1px solid #c46a52;
      |  background: #2a2b2e;
      |  color: #e8e6dc;
      |}
      |.mechanoid-live-actions button:hover {
      |  filter: brightness(1.1);
      |}
      |.mechanoid-live-actions button:disabled {
      |  opacity: 1;
      |  cursor: not-allowed;
      |  filter: none;
      |  color: #9a9b9f;
      |  border-color: #4a4b50;
      |  background: #1a1b1e;
      |}
      |.mechanoid-role-grid {
      |  display: grid;
      |  grid-template-columns: repeat(auto-fit, minmax(14rem, 1fr));
      |  gap: 0.85rem;
      |}
      |.mechanoid-role-card {
      |  border: 1px solid #4a4b50;
      |  border-radius: 6px;
      |  padding: 0.75rem 0.85rem 0.9rem;
      |  background: #222326;
      |  color: #e8e6dc;
      |}
      |.mechanoid-role-card h3 {
      |  margin: 0 0 0.25rem;
      |  font-size: 1rem;
      |  color: #f4f2ea;
      |  font-weight: 600;
      |}
      |.mechanoid-role-blurb {
      |  margin: 0 0 0.65rem;
      |  font-size: 0.85rem;
      |  color: #b8b9bd;
      |}
      |.mechanoid-role-card .mechanoid-live-actions {
      |  flex-direction: column;
      |  align-items: stretch;
      |}
      |.mechanoid-role-card .mechanoid-live-actions button {
      |  text-align: left;
      |}
      |.mechanoid-ticket-index-demo {
      |  color: #e8e6dc;
      |}
      |.mechanoid-ticket-index-demo .note {
      |  margin: 0;
      |  color: #b8b9bd;
      |  font-size: 0.9rem;
      |}
      |.mechanoid-ticket-field {
      |  display: flex;
      |  flex-direction: column;
      |  gap: 0.35rem;
      |  margin: 0 0 0.75rem;
      |}
      |.mechanoid-ticket-field > span {
      |  font-size: 0.75rem;
      |  letter-spacing: 0.04em;
      |  text-transform: uppercase;
      |  color: #9a9b9f;
      |}
      |.mechanoid-ticket-field-row {
      |  display: flex;
      |  gap: 0.45rem;
      |  align-items: center;
      |  flex-wrap: wrap;
      |}
      |.mechanoid-ticket-index-demo button {
      |  font: inherit;
      |  padding: 0.4rem 0.9rem;
      |  cursor: pointer;
      |  border-radius: 4px;
      |  border: 1px solid #c46a52;
      |  background: #2a2b2e;
      |  color: #e8e6dc;
      |}
      |.mechanoid-ticket-index-demo button:hover {
      |  filter: brightness(1.1);
      |}
      |.mechanoid-ticket-index-demo button.is-on {
      |  background: #c46a52;
      |  border-color: #d27b63;
      |  color: #1c1d1f;
      |}
      |.mechanoid-ticket-index-demo input[type="text"],
      |.mechanoid-ticket-index-demo input[type="number"] {
      |  font: inherit;
      |  min-width: 6rem;
      |  flex: 1;
      |  padding: 0.4rem 0.65rem;
      |  border-radius: 4px;
      |  border: 1px solid #4a4b50;
      |  background: #1a1b1e;
      |  color: #f4f2ea;
      |}
      |.mechanoid-ticket-status {
      |  min-height: 1.2rem;
      |  margin: 0.55rem 0 0;
      |  font-size: 0.85rem;
      |  color: #e8a090;
      |}
      |.mechanoid-ticket-counts {
      |  margin: 0 0 0.65rem;
      |  font-size: 0.85rem;
      |  color: #b8b9bd;
      |}
      |.mechanoid-ticket-list {
      |  list-style: none;
      |  margin: 0 0 0.75rem;
      |  padding: 0;
      |  display: flex;
      |  flex-direction: column;
      |  gap: 0.45rem;
      |}
      |.mechanoid-ticket-row {
      |  display: flex;
      |  flex-wrap: wrap;
      |  align-items: center;
      |  gap: 0.5rem 0.75rem;
      |  padding: 0.55rem 0.65rem;
      |  border: 1px solid #4a4b50;
      |  border-radius: 4px;
      |  background: #1a1b1e;
      |}
      |.mechanoid-ticket-row-id {
      |  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
      |  font-size: 0.9rem;
      |  color: #f4f2ea;
      |}
      |.mechanoid-ticket-row-state {
      |  font-weight: 600;
      |  font-size: 0.85rem;
      |  color: #e8a090;
      |}
      |.mechanoid-ticket-row-meta {
      |  font-size: 0.8rem;
      |  color: #b8b9bd;
      |  margin-right: auto;
      |}
      |.mechanoid-ticket-empty {
      |  margin: 0.25rem 0 0.75rem;
      |  font-size: 0.85rem;
      |  color: #9a9b9f;
      |}
      |.mechanoid-ticket-row .mechanoid-live-actions {
      |  flex-direction: row;
      |  align-items: center;
      |}
      |""".stripMargin

  val layers: ZLayer[Any, Nothing, SiteBuilder] =
    Theme.fromTokens(
      EarlyEffectTheme.tokens.copy(extraCss = EarlyEffectTheme.tokens.extraCss + liveCss)
    ) >>> DocsSite.themedStack
end DocsChrome
