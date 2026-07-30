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
      |""".stripMargin

  val tokens: ThemeTokens =
    EarlyEffectTheme.tokens.copy(
      diagramConfig = diagramConfig,
      extraCss = EarlyEffectTheme.tokens.extraCss + diagramCss,
    )

  val layers: ZLayer[Any, Nothing, SiteBuilder] =
    Theme.fromTokens(tokens) >>> DocsSite.themedStack
end DocsDiagrams
