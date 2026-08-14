package mechanoid.docs

import earlyeffect.docs.EarlyEffectTheme
import specular.*
import specular.site.*
import zio.*

import java.nio.file.{Files, Path, Paths, StandardCopyOption}

/** Specular DocsSite: Test classpath main invoked by `docs/specularSite`. */
object BuildSite extends DocsSite:

  @navLabel("Getting Started")
  final case class GettingStartedNav(
      overview: Overview.type,
      why: WhyMechanoid.type,
      quickStart: QuickStart.type,
      testing: Testing.type,
  )

  @navLabel("Defining Machines")
  final case class DefiningNav(
      concepts: CoreConcepts.type,
      defining: DefiningFsms.type,
      sideEffects: SideEffects.type,
  )

  @navLabel("Running & Production")
  final case class RunningNav(
      running: RunningFsms.type,
      persistence: Persistence.type,
      timeouts: DurableTimeouts.type,
      distributed: DistributedCoordination.type,
      browser: Interactive.type,
  )

  @navLabel("Domains")
  final case class DomainsNav(
      documentWorkflow: DocumentWorkflow.type,
      heartbeat: ServiceHeartbeat.type,
      orders: Orders.type,
  )

  @navLabel("Reference")
  final case class ReferenceNav(
      visualization: Visualization.type,
      reference: Reference.type,
      examples: Examples.type,
  )

  final case class MechanoidNav(
      gettingStarted: GettingStartedNav,
      defining: DefiningNav,
      running: RunningNav,
      domains: DomainsNav,
      reference: ReferenceNav,
  ) derives SiteNav

  private val siteNav: NavModel = SiteNav[MechanoidNav].toNavModel

  def pages: Vector[DocPage] = siteNav.pages

  override def site: SiteModel =
    val m       = meta
    val version = m.docsVersion
    EarlyEffectTheme
      .brand(super.site)
      .copy(
        nav = Some(siteNav),
        pages = siteNav.pages,
        clientScript = Some("assets/client.js"),
        summaryMarkdown = Some(
          """**Mechanoid** makes domain workflows an explicit, typed state graph on ZIO:
enums (or sealed traits) for states and events, a tailored DSL for transitions and
composition, and assemblies validated at compile time. Keep writing ZIO for effects;
add persistence, durable timeouts, and distributed coordination as optional layers when
the domain needs them.

Every page here is a Specular DocSpec: examples assert under zio-test, and machines are
rendered with mermoid so the picture cannot drift from the code. Browser persistence
uses Scala.js + IndexedDB (`mechanoid-web`) with multi-tab sync.
"""
        ),
        installSnippets = Vector(
          CodeSnippet(
            "Core (JVM)",
            s"""libraryDependencies += "rocks.earlyeffect" %% "mechanoid" % "$version"
libraryDependencies += "dev.zio" %% "zio" % "2.1.26" // provided by mechanoid""",
          ),
          CodeSnippet(
            "Core (Scala.js)",
            s"""libraryDependencies += "rocks.earlyeffect" %%% "mechanoid" % "$version"
libraryDependencies += "dev.zio" %%% "zio" % "2.1.26\"""",
          ),
          CodeSnippet(
            "Browser IndexedDB (Scala.js)",
            s"""libraryDependencies += "rocks.earlyeffect" %%% "mechanoid-web" % "$version\"""",
          ),
          CodeSnippet(
            "PostgreSQL (optional, JVM)",
            s"""libraryDependencies += "rocks.earlyeffect" %% "mechanoid-postgres" % "$version\"""",
          ),
        ),
        brand = Some(
          Brand(
            name = m.title.getOrElse("mechanoid"),
            links = Vector(EarlyEffectTheme.github("https://github.com/early-effect/mechanoid")),
          )
        ),
      )
  end site

  override def layers: ZLayer[Any, Nothing, SiteBuilder] =
    DocsChrome.layers

  override def afterBuild(out: Path, result: SiteOutput): Task[Unit] =
    val _ = result
    EarlyEffectTheme.writeLogo(out) *> copyClientBundle(out)

  private def copyClientBundle(out: Path): Task[Unit] =
    ZIO.attempt {
      val dest = out.resolve("assets/client.js")
      val src  = findClientJs.getOrElse {
        throw new RuntimeException(
          "JS client not linked; run docs/specularJsLink (or docs/specularSite) first. " +
            s"Looked for marker ${clientJsMarker}"
        )
      }
      Files.createDirectories(dest.getParent)
      Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING)
      ()
    }

  private def findClientJs: Option[Path] =
    readMarker.orElse(walkTargetOut)

  private def readMarker: Option[Path] =
    val marker = clientJsMarker
    if !Files.isRegularFile(marker) then None
    else
      val line = Files.readString(marker).nn.trim
      if line.isEmpty then None
      else
        val path = Paths.get(line)
        Option.when(Files.isRegularFile(path))(path)

  private def walkTargetOut: Option[Path] =
    val outRoot = repoRoot.resolve("target/out")
    if !Files.isDirectory(outRoot) then None
    else
      val stream = Files.walk(outRoot)
      try
        val found = stream
          .filter { p =>
            val s = p.toString.replace('\\', '/')
            s.endsWith("mechanoid-docs-fastopt/main.js") || s.endsWith("docsJS-fastopt/main.js")
          }
          .findFirst()
        if found.isPresent then Some(found.get.nn) else None
      finally stream.close()
    end if
  end walkTargetOut

  private def clientJsMarker: Path =
    repoRoot.resolve("target/specular-client-js.path")

  private def repoRoot: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath.nn)(p => Option(p.getParent).orNull)
      .takeWhile(_ != null)
      .find(p => Files.exists(p.resolve("build.sbt")))
      .getOrElse(Paths.get("").toAbsolutePath.nn)
end BuildSite
