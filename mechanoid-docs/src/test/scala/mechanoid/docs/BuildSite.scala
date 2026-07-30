package mechanoid.docs

import earlyeffect.docs.EarlyEffectTheme
import specular.*
import specular.site.*
import zio.*

import java.nio.file.Path

/** Specular DocsSite: Test classpath main invoked by `docs/specularSite`. */
object BuildSite extends DocsSite:

  @navLabel("Getting Started")
  final case class GettingStartedNav(
      overview: Overview.type,
      why: WhyMechanoid.type,
      quickStart: QuickStart.type,
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
        summaryMarkdown = Some(
          """**Mechanoid** is a type-safe finite state machine library for Scala 3 and ZIO.
It sits comfortably alongside the effects you already write: states and events as enums,
transitions as ZIO effects, and assemblies validated at compile time. Start in memory,
then add persistence, durable timeouts, and distributed coordination as ZIO layers when
your domain needs them.

Every example on this site is a Specular DocSpec: it asserts under zio-test when the site
is built.
"""
        ),
        installSnippets = Vector(
          CodeSnippet(
            "Core",
            s"""libraryDependencies += "rocks.earlyeffect" %% "mechanoid" % "$version"
libraryDependencies += "dev.zio" %% "zio" % "2.1.26" // provided by mechanoid""",
          ),
          CodeSnippet(
            "PostgreSQL (optional)",
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
    DocsDiagrams.layers

  override def afterBuild(out: Path, result: SiteOutput): Task[Unit] =
    val _ = result
    EarlyEffectTheme.writeLogo(out)
end BuildSite
