import zipx.*

/** Typed catalog: every library and plugin this build may use. `zipxDepUpdate` rewrites constructors here.
  *
  * sbt-zipx is not a row: generate emits it from the loaded plugin (`zipxSelfPlugins`). sbt-pgp is not a row: zipx
  * already brings it in. Action pins stay on jar defaults. Extra CI steps that zipx does not emit (setup-node + npm ci)
  * keep their SHA in `build.sbt`.
  *
  * Parent `Lib` vals used only for `.mod` are catalog rows; they are not `library()`-selected when another selected
  * module already pulls them (specular-core / specular-site via the docs theme).
  */
object MyVersions extends ZipxVersions:
  val sbt: SbtVersion     = SbtVersion("2.0.7")
  val scala: ScalaVersion = ScalaVersion("3.8.4")

  val zio             = Lib("dev.zio", "zio", "2.1.26")
  val zioStreams      = zio.mod("zio-streams")
  val zioTest         = zio.mod("zio-test")
  val zioTestSbt      = zio.mod("zio-test-sbt")
  val zioTestMagnolia = zio.mod("zio-test-magnolia")
  val zioJson         = Lib("dev.zio", "zio-json", "1.0.0")
  val zioLogging      = Lib("dev.zio", "zio-logging", "2.5.3")
  val zioLoggingSlf4j = zioLogging.mod("zio-logging-slf4j")
  val zioLoggingBridge = zioLogging.mod("zio-logging-slf4j-bridge")

  val scalaJavaTime     = Lib("io.github.cquiroz", "scala-java-time", "2.7.0")
  val scalaJavaTimeTzdb = scalaJavaTime.mod("scala-java-time-tzdb")

  val saferis                  = Lib("rocks.earlyeffect", "saferis", "0.19.1")
  val postgresql               = Lib("org.postgresql", "postgresql", "42.7.13").java
  val testcontainersPostgresql = Lib("org.testcontainers", "testcontainers-postgresql", "2.0.5").java
  val commonsCompress          = Lib("org.apache.commons", "commons-compress", "1.28.0").java
  val scalajsDom               = Lib("org.scala-js", "scalajs-dom", "2.8.1")
  val scaluzzi                 = Lib("com.github.vovapolu", "scaluzzi", "0.1.23")

  val specular        = Lib("rocks.earlyeffect", "specular-core", "0.14.1")
  val specularZioTest = specular.mod("specular-zio-test")
  val specularTheme   = specular.mod("early-effect-docs-theme")
  val specularMermoid = specular.mod("specular-mermoid")
  val ascentJs        = Lib("rocks.earlyeffect", "ascent-js", "0.5.0")
  val ascentCss       = ascentJs.mod("ascent-css")

  val scalajs = Plugin("org.scala-js", "sbt-scalajs", "1.22.0")
  // sbt-scalafmt pulls _2.13 variants via scalafmt-dynamic (for3Use2_13), which clash with _3 variants
  // from sbt-scalafix and sbt-scoverage on sbt 2. Keep the exclude on the row so generate preserves it.
  val scalafmt = Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")
    .excluding(
      ZipxExclude.org("org.scala-lang.modules", "scala-xml_2.13"),
      ZipxExclude.org("org.scala-lang.modules", "scala-collection-compat_2.13"),
      ZipxExclude.org("com.github.plokhotnyuk.jsoniter-scala", "jsoniter-scala-core_2.13"),
    )
  val specularPlugin = Plugin("rocks.earlyeffect", "sbt-specular", "0.14.1")
  val sbtAssembly    = Plugin("com.eed3si9n", "sbt-assembly", "2.5.0")
  val scalafix       = Plugin("ch.epfl.scala", "sbt-scalafix", "0.14.7")
  val dynverCi       = Plugin("rocks.earlyeffect", "sbt-dynver-ci", "0.2.3")
  val scoverage      = Plugin("org.scoverage", "sbt-scoverage", "2.4.4")
  val sbtReload      = Plugin("com.jamesward", "sbt-reload", "0.0.8")

  def zioLib          = library(zio, zioStreams, zioJson)
  def zioTests        = library(zioTest.test, zioTestSbt.test)
  def zioTestsMagnolia = library(zioTest.test, zioTestSbt.test, zioTestMagnolia.test)
  def zioLoggingLib   = library(zioLogging, zioLoggingSlf4j, zioLoggingBridge)
  def javaTime        = library(scalaJavaTime, scalaJavaTimeTzdb)
  def postgresLib     = library(saferis, postgresql)
  def postgresTests   = library(testcontainersPostgresql.test, zioTest.test, zioTestSbt.test)
  def webLib          = library(scalajsDom)
  def docsJvm         = library(specularZioTest.test, specularTheme.test)
  def docsJs          = library(specular, specularMermoid, ascentJs, ascentCss, zio, zioJson)
end MyVersions
