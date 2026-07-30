package mechanoid.docs

import earlyeffect.docs.EarlyEffectTheme
import mermoid.{LayoutConfig, RenderConfig}
import mermoid.css.{Theme as MermoidTheme, *}
import specular.mermoid.Mermoid
import specular.site.{DocsSite, SiteBuilder, Theme, ThemeTokens}
import zio.ZLayer

/** Shared Mermaid styling for Mechanoid DocSpecs (fenced `mermaid` blocks pick this up via [[BuildSite]]). */
object DocsDiagrams:

  private val colors: ThemeColors =
    Mermoid.chalkboardColors.copy(fontSize = "17px")

  private val pathColors: Stylesheet = Stylesheet(
    rules = List(
      nodeFill("sad", "#5c2a2a", "#f0a0a0"),
      nodeFill("happy", "#1f4a35", "#7dcea0"),
      nodeFill("warn", "#4a4030", "#e0c070"),
      CssRule(
        CssSelector.Class("subgraph-rect"),
        List(
          CssDeclaration("fill", CssValue.Color("#222326")),
          CssDeclaration("stroke", CssValue.Color("#5a5750")),
          CssDeclaration("stroke-width", CssValue.Str("1.5")),
          CssDeclaration("stroke-dasharray", CssValue.Str("4 3")),
        ),
      ),
      CssRule(
        CssSelector.Class("subgraph-label"),
        List(
          CssDeclaration("fill", CssValue.Color("#c4c0b4")),
          CssDeclaration("font-size", CssValue.Str("15px")),
        ),
      ),
      CssRule(
        CssSelector.Class("edge-label"),
        List(CssDeclaration("font-size", CssValue.Str("15px"))),
      ),
      CssRule(
        CssSelector.Class("note-text"),
        List(CssDeclaration("font-size", CssValue.Str("15px"))),
      ),
    )
  )

  private def nodeFill(cls: String, fill: String, stroke: String): CssRule =
    CssRule(
      CssSelector.Descendant(CssSelector.Class(cls), CssSelector.Class("node-shape")),
      List(
        CssDeclaration("fill", CssValue.Color(fill)),
        CssDeclaration("stroke", CssValue.Color(stroke)),
      ),
    )

  private val layout: LayoutConfig =
    LayoutConfig(
      hSpacing = 56.0,
      vSpacing = 64.0,
      padding = 28.0,
      fontSize = 17,
      edgeLabelFontSize = 15,
      nodePaddingH = 28.0,
    )

  val diagramConfig: RenderConfig =
    RenderConfig(
      theme = ThemeName.Dark,
      layout = layout,
      customStylesheet = Some(Stylesheet.merge(MermoidTheme.toStylesheet(colors), pathColors)),
    )

  private val diagramCss: String =
    """
      |/* mechanoid docs: mermaid SVGs reflow in the content column */
      |.specular-site-Theme-Content svg[viewBox] {
      |  max-width: 100%;
      |  height: auto;
      |  display: block;
      |  margin: 0.75rem 0 1.25rem;
      |}
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
      |.mechanoid-live-fsm .mermoid-node.is-selected {
      |  outline: 3px solid #c46a52;
      |  outline-offset: 3px;
      |  filter: brightness(1.12);
      |  transition: outline-color 180ms ease, filter 180ms ease;
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
      |""".stripMargin

  val tokens: ThemeTokens =
    EarlyEffectTheme.tokens.copy(
      diagramConfig = diagramConfig,
      extraCss = EarlyEffectTheme.tokens.extraCss + diagramCss,
    )

  val layers: ZLayer[Any, Nothing, SiteBuilder] =
    Theme.fromTokens(tokens) >>> DocsSite.themedStack
end DocsDiagrams
