import org.scalajs.linker.interface.ModuleKind
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.*
// sbt has its own `Exec` (a queued command), so the two wildcards collide. An explicit named
// import outranks both, and this is the one we mean: the shell AST's simple command.
import zipx.shell.Exec

val scala3Version = "3.8.4"
val zioVersion    = "2.1.26"
val scalaVersions = Seq(scala3Version)

// lets enable semanticdb
ThisBuild / semanticdbEnabled := true

// Global settings. Iterable/mechanoid overrides group via PUBLISH_ORG from ZipxGitHubPackages.
ThisBuild / scalaVersion         := scala3Version
ThisBuild / organization         := sys.env.getOrElse("PUBLISH_ORG", "rocks.earlyeffect")
ThisBuild / organizationName     := sys.env.getOrElse("PUBLISH_ORG_NAME", "Early Effect")
ThisBuild / organizationHomepage := Some(url("https://www.earlyeffect.rocks"))
ThisBuild / licenses             := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt"))
ThisBuild / homepage             := Some(url("https://github.com/early-effect/mechanoid"))
ThisBuild / scmInfo              := Some(
  ScmInfo(
    url("https://github.com/early-effect/mechanoid"),
    "scm:git@github.com:early-effect/mechanoid.git",
  )
)
ThisBuild / developers := List(
  Developer(
    id = "russwyte",
    name = "Russ White",
    email = "356303+russwyte@users.noreply.github.com",
    url = url("https://github.com/russwyte"),
  )
)
ThisBuild / versionScheme := Some("early-semver")

// Dual publish: Central by default; GitHub Packages when CI sets PUBLISH_PACKAGES_REPO.
val githubPackagesRepo: Option[MavenRepository] =
  sys.env.get("PUBLISH_PACKAGES_REPO").map("GitHub Package Registry" at _)

ThisBuild / credentials ++= sys.env
  .get("GITHUB_TOKEN")
  .map { token =>
    Credentials("GitHub Package Registry", "maven.pkg.github.com", "_", token)
  }
  .toSeq

ThisBuild / resolvers ++= githubPackagesRepo.toSeq

ThisBuild / publishTo := githubPackagesRepo.orElse {
  val centralSnapshots =
    "https://central.sonatype.com/repository/maven-snapshots/"
  if (isSnapshot.value) Some("central-snapshots" at centralSnapshots)
  else localStaging.value
}

// CI-only Central signing. Fork Packages publishes are unsigned (token auth).
githubPackagesRepo match {
  case None    => usePgpKeyHex(sys.env.getOrElse("PGP_KEY_HEX", "MISSING_KEY_HEX"))
  case Some(_) => Seq.empty
}

val mechanoidJavaOpts = Map("JAVA_OPTS" -> EnvValue.plain("-Dfile.encoding=UTF-8"))

// Shared capability names as vals, so a reference to one is checked rather than spelled twice.
val Fmt     = CapabilityName("fmt")
val TestJvm = CapabilityName("test-jvm")
val TestJs  = CapabilityName("test-js")

val mechanoidJsCiSetup = Steps.built("mechanoid-js-ci")(
  // Step.uses is inline, so an unpinned or malformed ref is a compile error naming it.
  Step
    .uses("actions/setup-node@820762786026740c76f36085b0efc47a31fe5020") // v7.0.0
    .named("Set up Node")
    .withInputs(scala.collection.immutable.ListMap("node-version" -> "24", "cache" -> "npm")),
  Step.run(Script(Exec("npm", Word.lit("ci")))).named("Install Node dependencies (jsdom, fake-indexeddb)"),
)

/** Pre-pull with retries. Verbatim shell, so runRaw declares the escape hatch and earns a generate-time warning naming
  * the step, rather than hiding it in a bare `run =`.
  */
val postgresPrePull = Steps.built("postgres-pre-pull")(
  Step
    .runRaw(
      """|set -euo pipefail
         |image=postgres:latest
         |max=5
         |for attempt in $(seq 1 "$max"); do
         |  if docker pull "$image"; then
         |    exit 0
         |  fi
         |  if [ "$attempt" -eq "$max" ]; then
         |    echo "Failed to pull $image after $max attempts" >&2
         |    exit 1
         |  fi
         |  sleep $((attempt * 10))
         |done
         |""".stripMargin
    )
    .named("Pre-pull Postgres image")
)

// zipx: platform verify + dual publish by repo + Steward + Pages.
zipxJavaVersion      := JdkVersion("25")
zipxScalaSteward     := true
zipxWorkflowDispatch := true
zipxCapabilities ++= {
  val upstream = JobCondition.repositoryIs("early-effect/mechanoid")
  Seq(
    zipxTasks.once(Fmt, scalafmtCheckAll),
    Capability.once(
      name = TestJvm,
      // A command alias, not a task key, so it stays a literal SbtCommand.
      command = SbtCommand("testJVM"),
      needsCapabilities = List(Fmt),
      env = Map("TESTCONTAINERS_RYUK_DISABLED" -> EnvValue.plain("true")) ++ mechanoidJavaOpts,
      extraSteps = postgresPrePull,
    ),
    Capability.once(
      name = TestJs,
      command = SbtCommand("testJS"),
      needsCapabilities = List(Fmt),
      extraSteps = mechanoidJsCiSetup,
      env = mechanoidJavaOpts,
    ),
    // Keep required-check name `test` stable; waits on both platforms.
    Capability.once(
      name = Capability.TestName,
      command = SbtCommand("about"),
      needsCapabilities = List(TestJvm, TestJs),
    ),
    ZipxCentral.release
      .copy(command =
        _ =>
          Some(
            SbtCommand(
              "core/publishSigned; coreJS/publishSigned; webJS/publishSigned; postgres/publishSigned; sonaRelease"
            )
          )
      )
      .withCondition(upstream),
    ZipxGitHubPackages.sharedRegistry(
      // 0.1.6 dropped the `repository` param, which used to become this fork gate implicitly.
      // Stated explicitly so the Packages publish still cannot run outside Iterable/mechanoid.
      condition = Some(JobCondition.repositoryIs("Iterable/mechanoid")),
      packagesRepo = Some("https://maven.pkg.github.com/iterable/maven-packages"),
      publishOrg = Some("com.iterable"),
      publishOrgName = Some("Iterable"),
    ),
    ZipxDocs.pages().andCondition(upstream),
  )
}

val javaTimePolyfill = Def.settings(
  libraryDependencies ++= Seq(
    "io.github.cquiroz" %% "scala-java-time"      % "2.7.0",
    "io.github.cquiroz" %% "scala-java-time-tzdb" % "2.7.0",
  )
)

val zioProvided = Def.settings(
  libraryDependencies ++= Seq(
    "dev.zio" %% "zio"         % zioVersion % "provided",
    "dev.zio" %% "zio-streams" % zioVersion % "provided",
    "dev.zio" %% "zio-json"    % "0.10.0"   % "provided",
  )
)

val zioTestSettings = Def.settings(
  libraryDependencies ++= Seq(
    "dev.zio" %% "zio-test"          % zioVersion % Test,
    "dev.zio" %% "zio-test-sbt"      % zioVersion % Test,
    "dev.zio" %% "zio-test-magnolia" % zioVersion % Test,
  )
)

val jsdomTestEnv = Def.settings(
  Test / jsEnv := Def.uncached(new org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv())
)

lazy val commonScalacOptions = Seq(
  "-deprecation",
  "-Wunused:all",
  "-feature",
)

lazy val commonSettings = Seq(
  scalacOptions ++= commonScalacOptions,
  scalafixDependencies += "com.github.vovapolu" %% "scaluzzi" % "0.1.23",
)

lazy val coverageSettings = Seq(
  coverageExcludedPackages := "mechanoid\\.macros\\..*;mechanoid\\.machine\\.Macros.*;mechanoid\\.machine\\.MacroUtils.*;mechanoid\\.machine\\.AssemblyMacros.*;mechanoid\\.machine\\.MachineMacros.*;mechanoid\\.machine\\.ProducingMacros.*;mechanoid\\.core\\.Finite.*;mechanoid\\.core\\.Redactor.*;mechanoid\\.Mechanoid\\$package.*",
  coverageFailOnMinimum      := true,
  coverageMinimumStmtTotal   := 95,
  coverageMinimumBranchTotal := 95,
)

lazy val publishSettings = Seq(
  publishMavenStyle    := true,
  pomIncludeRepository := { _ => false },
)

addCommandAlias("testCoverage", "clean; coverage; test; coverageAggregate; coverageReport")

commands += Command.single("moduleCoverage") { (state, module) =>
  s"clean; coverage; $module/test; $module/coverageReport" :: state
}

lazy val root = project
  .in(file("."))
  .aggregate(
    (core.projectRefs ++ web.projectRefs ++ docs.projectRefs ++
      Seq[ProjectReference](postgres, examples, compileTimeChecks))*
  )
  .settings(
    name           := "mechanoid-root",
    publish / skip := true,
    test / skip    := true,
  )

lazy val core = (projectMatrix in file("core"))
  .settings(
    name        := "mechanoid",
    description := "A type-safe, effect-oriented finite state machine library for Scala built on ZIO",
    commonSettings,
    publishSettings,
    zioProvided,
    zioTestSettings,
  )
  .jvmPlatform(
    scalaVersions = scalaVersions,
    settings = coverageSettings ++ Seq(
      libraryDependencies ++= Seq(
        "dev.zio" %% "zio-logging"              % "2.5.3" % "provided",
        "dev.zio" %% "zio-logging-slf4j"        % "2.5.3" % "provided",
        "dev.zio" %% "zio-logging-slf4j-bridge" % "2.5.3" % "provided",
      )
    ),
  )
  .jsPlatform(
    scalaVersions = scalaVersions,
    settings = javaTimePolyfill,
  )

lazy val postgres = project
  .in(file("postgres"))
  .dependsOn(core.jvm(scala3Version) % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(publishSettings)
  .settings(coverageSettings)
  .settings(
    name        := "mechanoid-postgres",
    description := "PostgreSQL persistence implementation for Mechanoid FSM library",
    libraryDependencies ++= Seq(
      "dev.zio"           %% "zio"                       % zioVersion % "provided",
      "dev.zio"           %% "zio-streams"               % zioVersion % "provided",
      "dev.zio"           %% "zio-json"                  % "0.10.0"   % "provided",
      "rocks.earlyeffect" %% "saferis"                   % "0.19.1",
      "org.postgresql"     % "postgresql"                % "42.7.13",
      "org.testcontainers" % "testcontainers-postgresql" % "2.0.5"    % Test,
      "dev.zio"           %% "zio-test"                  % zioVersion % Test,
      "dev.zio"           %% "zio-test-sbt"              % zioVersion % Test,
    ),
    dependencyOverrides ++= Seq(
      "org.apache.commons" % "commons-compress" % "1.28.0"
    ),
  )

lazy val examples = project
  .in(file("examples"))
  .dependsOn(core.jvm(scala3Version), postgres)
  .settings(commonSettings)
  .settings(
    name           := "mechanoid-examples",
    publish / skip := true,
    zipxPublish    := Some(false),
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio"                      % zioVersion,
      "dev.zio" %% "zio-streams"              % zioVersion,
      "dev.zio" %% "zio-json"                 % "0.10.0",
      "dev.zio" %% "zio-logging"              % "2.5.3",
      "dev.zio" %% "zio-logging-slf4j"        % "2.5.3",
      "dev.zio" %% "zio-logging-slf4j-bridge" % "2.5.3",
      "dev.zio" %% "zio-test"                 % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt"             % zioVersion % Test,
    ),
    assembly / mainClass             := Some("mechanoid.examples.heartbeat.Main"),
    assembly / assemblyJarName       := "app.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "MANIFEST.MF")                                       => MergeStrategy.discard
      case PathList("META-INF", xs @ _*) if xs.lastOption.exists(_.endsWith(".SF"))  => MergeStrategy.discard
      case PathList("META-INF", xs @ _*) if xs.lastOption.exists(_.endsWith(".DSA")) => MergeStrategy.discard
      case PathList("META-INF", xs @ _*) if xs.lastOption.exists(_.endsWith(".RSA")) => MergeStrategy.discard
      case PathList("META-INF", "services", _*)                                      => MergeStrategy.concat
      case PathList("reference.conf")                                                => MergeStrategy.concat
      case _                                                                         => MergeStrategy.first
    },
  )

lazy val compileExperiments = project
  .in(file("compile-experiments"))
  .dependsOn(core.jvm(scala3Version))
  .settings(commonSettings)
  .settings(
    name           := "compile-experiments",
    publish / skip := true,
    zipxPublish    := Some(false),
  )

lazy val compileTimeChecks = project
  .in(file("compile-time-checks"))
  .dependsOn(core.jvm(scala3Version))
  .settings(
    name           := "compile-time-checks",
    publish / skip := true,
    zipxPublish    := Some(false),
    scalacOptions ++= Seq(
      "-Werror",
      "-deprecation",
      "-feature",
    ),
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-test"     % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
  )

val specularVersion = "0.12.1"
val ascentVersion   = "0.4.0"

lazy val specularPreview =
  taskKey[Unit]("Build specularSite then serve with sbt-reload (prefer alias: docsPreview)")

lazy val specularJsLink =
  taskKey[Unit]("Link docsJS client and write marker path for BuildSite.afterBuild")

// --- mechanoid-web : IndexedDB persistence for browsers (JS only) ---
lazy val web = (projectMatrix in file("web"))
  .dependsOn(core)
  .settings(
    name        := "mechanoid-web",
    description := "IndexedDB persistence and multi-tab sync for Mechanoid on Scala.js",
    commonSettings,
    publishSettings,
    javaTimePolyfill,
    libraryDependencies ++= Seq(
      "dev.zio"      %% "zio"         % zioVersion % "provided",
      "dev.zio"      %% "zio-streams" % zioVersion % "provided",
      "dev.zio"      %% "zio-json"    % "0.10.0"   % "provided",
      "org.scala-js" %% "scalajs-dom" % "2.8.1",
    ),
    zioTestSettings,
  )
  .jsPlatform(
    scalaVersions = scalaVersions,
    settings = Seq(
      // Node + CommonJS so @JSImport("fake-indexeddb/auto") works (JSDOM env rejects CommonJS).
      Test / jsEnv := Def.uncached(new org.scalajs.jsenv.nodejs.NodeJSEnv()),
      scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)),
    ),
  )

// --- docs : JVM site build + JS interactive client ---
lazy val docs = (projectMatrix in file("mechanoid-docs"))
  .settings(
    name            := "mechanoid-docs",
    publish / skip  := true,
    publishArtifact := false,
    zipxPublish     := Some(false),
    commonSettings,
  )
  .jvmPlatform(
    scalaVersions,
    Nil,
    (p: Project) =>
      p.dependsOn(core.jvm(scala3Version), postgres)
        .enablePlugins(SpecularPlugin)
        .settings(
          libraryDependencies ++= Seq(
            "dev.zio"           %% "zio"                     % zioVersion,
            "dev.zio"           %% "zio-streams"             % zioVersion,
            "dev.zio"           %% "zio-json"                % "0.10.0",
            "dev.zio"           %% "zio-test"                % zioVersion      % Test,
            "dev.zio"           %% "zio-test-sbt"            % zioVersion      % Test,
            "rocks.earlyeffect" %% "specular-core"           % specularVersion % Test,
            "rocks.earlyeffect" %% "specular-zio-test"       % specularVersion % Test,
            "rocks.earlyeffect" %% "specular-site"           % specularVersion % Test,
            "rocks.earlyeffect" %% "early-effect-docs-theme" % specularVersion % Test,
          ),
          libraryDependencySchemes += "dev.zio" %% "zio-json" % VersionScheme.Always,
          specularBuildMain                     := "mechanoid.docs.BuildSite",
          specularMetaProject                   := Some(LocalProject("core")),
          specularArtifactKind                  := "library",
          specularSiteDirectory                 := (ThisBuild / baseDirectory).value / "target" / "site",
          specularDisplayVersion                := {
            val v = (ThisBuild / version).value
            if (v.endsWith("-ci") || v.endsWith("-SNAPSHOT"))
              previousStableVersion.value.getOrElse("0.3.2")
            else ""
          },
          scalacOptions ~= (_.filterNot(_ == "-Wunused:all")),
          testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
          Test / mainClass       := Some("specular.site.DocsServe"),
          Test / run / mainClass := (Test / mainClass).value,
          Test / runReloadArgs   := {
            val siteDir = specularSiteDirectory.value
            Seq(specularPort.value.toString, siteDir.getAbsolutePath)
          },
          Test / run / javaOptions ++= {
            val dir = specularSiteDirectory.value.getAbsolutePath
            Seq(
              "--sun-misc-unsafe-memory-access=allow",
              "--enable-native-access=ALL-UNNAMED",
              s"-Dspecular.site.dir=$dir",
              s"-Dspecular.site.port=${specularPort.value}",
            )
          },
          specularJsLink := Def
            .uncached(Def.task {
              (LocalProject("docsJS") / Compile / fastLinkJS).value
              val outDir = (LocalProject("docsJS") / Compile / fastLinkJSOutput).value
              val mainJs = outDir / "main.js"
              if (!mainJs.exists)
                sys.error(
                  s"Expected $mainJs after fastLinkJS; directory contains: " +
                    Option(outDir.list).toSeq.flatten.mkString(", ")
                )
              val marker = (ThisBuild / baseDirectory).value / "target" / "specular-client-js.path"
              IO.write(marker, mainJs.getAbsolutePath)
            })
            .value,
          specularPreview := Def
            .uncached(Def.task {
              specularSite.value
              (Test / runReload).value
            })
            .value,
        ),
  )
  .jsPlatform(
    scalaVersions,
    Nil,
    (p: Project) =>
      p.dependsOn(core.js(scala3Version), web.js(scala3Version))
        .settings(
          javaTimePolyfill,
          libraryDependencies ++= Seq(
            "rocks.earlyeffect" %% "specular-core"    % specularVersion,
            "rocks.earlyeffect" %% "specular-mermoid" % specularVersion,
            "rocks.earlyeffect" %% "ascent-js"        % ascentVersion,
            "rocks.earlyeffect" %% "ascent-css"       % ascentVersion,
            "dev.zio"           %% "zio"              % zioVersion,
            "dev.zio"           %% "zio-json"         % "0.10.0",
          ),
          Compile / unmanagedSources ++= {
            val base =
              (ThisBuild / baseDirectory).value / "mechanoid-docs" / "src" / "test" / "scala" / "mechanoid" / "docs"
            Seq(
              base / "Interactive.scala",
              base / "ExampleRegistry.scala",
              base / "platform" / "OrderDemoUi.scala",
              base / "platform" / "PublishDemoUi.scala",
            )
          },
          scalaJSUseMainModuleInitializer := true,
          scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
          Compile / mainClass := Some("mechanoid.docs.ClientMain"),
          Test / skip         := true,
          Test / sources      := Nil,
        ),
  )

addCommandAlias("docsPreview", "~docs/specularPreview")

addCommandAlias(
  "testJVM",
  "core/test; postgres/test; examples/test; compileTimeChecks/test; docs/test; docs/specularSite",
)
addCommandAlias(
  "testJS",
  // sbt 2 `test` is testQuick; force full suites for Scala.js modules.
  "coreJS/testFull; webJS/testFull",
)
