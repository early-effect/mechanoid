package mechanoid.docs

import _root_.ascent.dsl.*
import mermoid.*

import scala.language.implicitConversions
import mermoid.ascent.MermoidAscent
import mermoid.css.*

/** Chalkboard paint for doc figures. Specular 0.20 does not ship this palette. */
object DocsDiagram:

  /** Charcoal, cream, terracotta. Matches the Early Effect docs theme. */
  val chalkboard: RenderConfig =
    val colors = ThemeColors(
      primaryColor = "#2a2b2e",
      primaryBorderColor = "#c46a52",
      primaryTextColor = "#e8e6dc",
      secondaryColor = "#3f4145",
      secondaryBorderColor = "#9a978c",
      secondaryTextColor = "#e8e6dc",
      tertiaryColor = "#121314",
      tertiaryBorderColor = "#3f4145",
      tertiaryTextColor = "#e8e6dc",
      lineColor = "#d4a574",
      textColor = "#e8e6dc",
      mainBkg = "#2a2b2e",
      nodeBorder = "#c46a52",
      background = "#1c1d1f",
      fontFamily = """"Avenir Next", Avenir, "Segoe UI", "Helvetica Neue", Helvetica, Arial, sans-serif""",
      fontSize = "17px",
      edgeLabelBackground = "#1c1d1f",
      noteBackground = "#3f4145",
      noteBorderColor = "#9a978c",
      noteTextColor = "#e8e6dc",
    )
    val layout = LayoutConfig(
      hSpacing = 56.0,
      vSpacing = 64.0,
      padding = 28.0,
      fontSize = 17,
      edgeLabelFontSize = 15,
      nodePaddingH = 28.0,
    )
    val extras = Stylesheet(
      variables = Map(ThemeVar.Selection.cssName -> CssValue.Color("#c46a52")),
      rules = List(
        nodeFill("sad", "#5c2a2a", "#f0a0a0"),
        nodeFill("happy", "#1f4a35", "#7dcea0"),
        nodeFill("warn", "#4a4030", "#e0c070"),
        CssRule(
          PaintClass.SubgraphRect.selector,
          List(
            CssDeclaration(CssProperty.Fill, CssValue.Color("#222326")),
            CssDeclaration(CssProperty.Stroke, CssValue.Color("#5a5750")),
            CssDeclaration(CssProperty.StrokeWidth, CssValue.Str("1.5")),
            CssDeclaration(CssProperty.StrokeDasharray, CssValue.Str("4 3")),
          ),
        ),
        CssRule(
          PaintClass.SubgraphLabel.selector,
          List(
            CssDeclaration(CssProperty.Fill, CssValue.Color("#c4c0b4")),
            CssDeclaration(CssProperty.FontSize, CssValue.Str("15px")),
          ),
        ),
        CssRule(
          PaintClass.EdgeLabel.selector,
          List(CssDeclaration(CssProperty.FontSize, CssValue.Str("15px"))),
        ),
        CssRule(
          PaintClass.NoteText.selector,
          List(CssDeclaration(CssProperty.FontSize, CssValue.Str("15px"))),
        ),
      ),
    )
    RenderConfig(
      theme = ThemeName.Dark,
      layout = layout,
      customStylesheet = Some(Stylesheet.merge(Theme.toStylesheet(colors), extras)),
    )
  end chalkboard

  /** A literal that already parsed. */
  def paint(mermaid: Mermaid, viewport: Option[Viewport] = None): _root_.ascent.ast.UI[Any] =
    MermoidAscent.diagram(mermaid, chalkboard, viewport)

  /** Text a `Machine` emitted. A parse failure is the figure, not an exception. */
  def paint(source: String): _root_.ascent.ast.UI[Any] =
    Mermaid.from(source) match
      case Right(mermaid) => paint(mermaid)
      case Left(error)    => _root_.ascent.E.pre(error.message)

  private def nodeFill(cls: String, fill: String, stroke: String): CssRule =
    CssRule(
      CssSelector.Descendant(CssSelector.Class(cls), PaintClass.NodeShape.selector),
      List(
        CssDeclaration(CssProperty.Fill, CssValue.Color(fill)),
        CssDeclaration(CssProperty.Stroke, CssValue.Color(stroke)),
      ),
    )
end DocsDiagram
