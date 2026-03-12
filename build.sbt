import xerial.sbt.Sonatype.sonatypeCentralHost

val scala3Version = "3.7.4"
val zioVersion    = "2.1.24"

ThisBuild / dependencyOverrides += "org.scalameta" % "semanticdb-scalac_2.12.21" % "4.14.4"
ThisBuild / scalaVersion                          := scala3Version
ThisBuild / organization                          := "io.github.russwyte"
ThisBuild / organizationName                      := "russwyte"
ThisBuild / organizationHomepage                  := Some(url("https://github.com/russwyte"))
ThisBuild / licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt"))
ThisBuild / homepage := Some(url("https://github.com/russwyte/mechanoid"))
ThisBuild / scmInfo  := Some(
  ScmInfo(
    url("https://github.com/russwyte/mechanoid"),
    "scm:git@github.com:russwyte/mechanoid.git",
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
ThisBuild / versionScheme          := Some("early-semver")
ThisBuild / sonatypeCredentialHost := sonatypeCentralHost

usePgpKeyHex("2F64727A87F1BCF42FD307DD8582C4F16659A7D6")

ThisBuild / libraryDependencies ++= Seq(
  "dev.zio" %% "zio"                      % zioVersion % "provided",
  "dev.zio" %% "zio-streams"              % zioVersion % "provided",
  "dev.zio" %% "zio-logging"              % "2.5.3"    % "provided",
  "dev.zio" %% "zio-logging-slf4j"        % "2.5.3"    % "provided",
  "dev.zio" %% "zio-logging-slf4j-bridge" % "2.5.3"    % "provided",

  "dev.zio" %% "zio-json"          % "0.8.0"    % "provided",
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
)

lazy val publishSettings = Seq(
  publishMavenStyle      := true,
  pomIncludeRepository   := { _ => false },
  sonatypeCredentialHost := sonatypeCentralHost,
  publishTo              := sonatypePublishToBundle.value,
)

lazy val root = project
  .in(file("."))
  .aggregate(core, postgres, examples)
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
      "io.github.russwyte" %% "saferis"      % "0.12.0",
      "org.postgresql"      % "postgresql"   % "42.7.10",
      "org.testcontainers"  % "postgresql"   % "1.21.4"   % Test,
      "dev.zio"            %% "zio-test"     % zioVersion % Test,
      "dev.zio"            %% "zio-test-sbt" % zioVersion % Test,
    ),
  )

lazy val examples = project
  .in(file("examples"))
  .dependsOn(core, postgres)
  .settings(commonSettings)
  .settings(
    name           := "mechanoid-examples",
    publish / skip := true,
    // ZIO deps are "provided" at root level, so examples needs them explicitly
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio"                      % zioVersion,
      "dev.zio" %% "zio-streams"              % zioVersion,
      "dev.zio" %% "zio-json"                 % "0.8.0",
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
  )

lazy val docs = project
  .in(file("mechanoid-docs"))
  .dependsOn(core, postgres)
  .enablePlugins(MdocPlugin)
  .settings(
    name           := "mechanoid-docs",
    publish / skip := true,
    mdocVariables  := Map(
      "VERSION" -> version.value
    ),
    mdocIn  := file("mechanoid-docs") / "docs",
    mdocOut := file("."),
    // ZIO deps are "provided" at root level, so mdoc needs them explicitly
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio"         % zioVersion,
      "dev.zio" %% "zio-streams" % zioVersion,
      "dev.zio" %% "zio-json"    % "0.8.0",
    ),
  )
