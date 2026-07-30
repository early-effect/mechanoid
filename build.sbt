val scala3Version = "3.8.4"
val zioVersion    = "2.1.26"
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

// zipx: Aggregate verify (tests + Specular docs site) + dual publish by repo + Steward + Pages.
zipxJavaVersion      := "25"
zipxScalaSteward     := true
zipxWorkflowDispatch := true
zipxCapabilities ++= {
  val upstream = JobCondition.repositoryIs("early-effect/mechanoid")
  Seq(
    Capability.once("fmt", "scalafmtCheckAll"),
    Capability.once(
      name = "test",
      command = "test; docs/specularSite",
      needsCapabilities = List("fmt"),
      // GHA VMs are disposable; skip Ryuk so Hub flakes on testcontainers/ryuk cannot fail CI.
      env = Map("TESTCONTAINERS_RYUK_DISABLED" -> EnvValue.plain("true")),
      extraSteps = _ =>
        List(
          Step(
            name = Some("Pre-pull Postgres image"),
            run = Some(
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
            ),
          )
        ),
    ),
    ZipxCentral.release
      .copy(command = _ => "core/publishSigned; postgres/publishSigned; sonaRelease")
      .withCondition(upstream),
    ZipxGitHubPackages.sharedRegistry(
      repository = Some("Iterable/mechanoid"),
      packagesRepo = Some("https://maven.pkg.github.com/iterable/maven-packages"),
      publishOrg = Some("com.iterable"),
      publishOrgName = Some("Iterable"),
    ),
    // Same org reusable workflow as peers; generated into ci.yml (no hand-rolled docs.yml).
    ZipxDocs.pages().andCondition(upstream),
  )
}

ThisBuild / libraryDependencies ++= Seq(
  "dev.zio" %% "zio"                      % zioVersion % "provided",
  "dev.zio" %% "zio-streams"              % zioVersion % "provided",
  "dev.zio" %% "zio-logging"              % "2.5.3"    % "provided",
  "dev.zio" %% "zio-logging-slf4j"        % "2.5.3"    % "provided",
  "dev.zio" %% "zio-logging-slf4j-bridge" % "2.5.3"    % "provided",

  "dev.zio" %% "zio-json"          % "0.10.0"   % "provided",
  "dev.zio" %% "zio-test"          % zioVersion % Test,
  "dev.zio" %% "zio-test-sbt"      % zioVersion % Test,
  "dev.zio" %% "zio-test-magnolia" % zioVersion % Test,
)

lazy val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-deprecation",
    "-Wunused:all",
    "-feature",
  ),
  scalafixDependencies += "com.github.vovapolu" %% "scaluzzi" % "0.1.23",
  // Exclude macros, inline methods, and export-only package objects from coverage
  // - Macros/inline run at compile time, incompatible with scoverage
  // - Mechanoid$package is just type re-exports with no runtime code
  coverageExcludedPackages := "mechanoid\\.macros\\..*;mechanoid\\.machine\\.Macros.*;mechanoid\\.machine\\.MacroUtils.*;mechanoid\\.machine\\.AssemblyMacros.*;mechanoid\\.machine\\.MachineMacros.*;mechanoid\\.machine\\.ProducingMacros.*;mechanoid\\.core\\.Finite.*;mechanoid\\.core\\.Redactor.*;mechanoid\\.Mechanoid\\$package.*",
  // Minimum coverage thresholds - fail build if coverage drops below these
  coverageFailOnMinimum      := true,
  coverageMinimumStmtTotal   := 95,
  coverageMinimumBranchTotal := 95,
)

lazy val publishSettings = Seq(
  publishMavenStyle    := true,
  pomIncludeRepository := { _ => false },
)

addCommandAlias("testCoverage", "clean; coverage; test; coverageAggregate; coverageReport")

// Parameterized coverage command: moduleCoverage <module>
// Example: sbt "moduleCoverage core" or sbt "moduleCoverage postgres"
commands += Command.single("moduleCoverage") { (state, module) =>
  s"clean; coverage; $module/test; $module/coverageReport" :: state
}

lazy val root = project
  .in(file("."))
  .aggregate(core, postgres, examples, compileTimeChecks, docs)
  .settings(
    name           := "mechanoid-root",
    publish / skip := true,
  )

lazy val core = project
  .in(file("core"))
  .settings(commonSettings)
  .settings(publishSettings)
  .settings(
    name        := "mechanoid",
    description := "A type-safe, effect-oriented finite state machine library for Scala built on ZIO",
  )

lazy val postgres = project
  .in(file("postgres"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(publishSettings)
  .settings(
    name        := "mechanoid-postgres",
    description := "PostgreSQL persistence implementation for Mechanoid FSM library",
    libraryDependencies ++= Seq(
      "rocks.earlyeffect" %% "saferis"      % "0.19.1",
      "org.postgresql"     % "postgresql"   % "42.7.13",
      "org.testcontainers" % "postgresql"   % "1.21.4"   % Test,
      "dev.zio"           %% "zio-test"     % zioVersion % Test,
      "dev.zio"           %% "zio-test-sbt" % zioVersion % Test,
    ),
    // Override vulnerable transitive deps from testcontainers -> docker-java
    dependencyOverrides ++= Seq(
      "com.fasterxml.jackson.core" % "jackson-core"        % "2.18.6",
      "com.fasterxml.jackson.core" % "jackson-annotations" % "2.18.6",
      "org.apache.commons"         % "commons-compress"    % "1.28.0",
    ),
  )

lazy val examples = project
  .in(file("examples"))
  .dependsOn(core, postgres)
  .settings(commonSettings)
  .settings(
    name           := "mechanoid-examples",
    publish / skip := true,
    zipxPublish    := Some(false),
    // ZIO deps are "provided" at root level, so examples needs them explicitly
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
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name           := "compile-experiments",
    publish / skip := true,
    zipxPublish    := Some(false),
  )

lazy val compileTimeChecks = project
  .in(file("compile-time-checks"))
  .dependsOn(core)
  .settings(
    name           := "compile-time-checks",
    publish / skip := true,
    zipxPublish    := Some(false),
    // Turn warnings into errors so typeCheck can catch them
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

val specularVersion = "0.11.0"

lazy val docs = project
  .in(file("mechanoid-docs"))
  .dependsOn(core, postgres)
  .enablePlugins(SpecularPlugin)
  .settings(commonSettings)
  .settings(
    name            := "mechanoid-docs",
    publish / skip  := true,
    publishArtifact := false,
    zipxPublish     := Some(false), // never join Central / Packages publish jobs
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
    // Specular's zio-schema-json still pins zio-json 0.9.x; mechanoid uses 0.10.x.
    libraryDependencySchemes += "dev.zio" %% "zio-json" % VersionScheme.Always,
    specularBuildMain                     := "mechanoid.docs.BuildSite",
    specularMetaProject                   := Some(LocalProject("core")),
    specularArtifactKind                  := "library",
    specularSiteDirectory                 := (ThisBuild / baseDirectory).value / "target" / "site",
    // Docs-only (workflow_dispatch) builds are dynver `-ci`; don't advertise that as a Central coord.
    // Empty string → Specular uses build version (clean v* tags).
    specularDisplayVersion := {
      val v = (ThisBuild / version).value
      if (v.endsWith("-ci") || v.endsWith("-SNAPSHOT"))
        previousStableVersion.value.getOrElse("0.3.2")
      else ""
    },
    scalacOptions ~= (_.filterNot(_ == "-Wunused:all")),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
  )

addCommandAlias("docsPreview", "~docs/specularPreview")
