// projectMatrix is built into sbt 2.x; only sbt-scalajs is needed for the JS platform row.
addSbtPlugin("org.scala-js"      % "sbt-scalajs"   % "1.22.0")
addSbtPlugin("org.scalameta"     % "sbt-scalafmt"  % "2.6.2")
addSbtPlugin("rocks.earlyeffect" % "sbt-specular"  % "0.12.1")
addSbtPlugin("com.eed3si9n"      % "sbt-assembly"  % "2.4.1")
addSbtPlugin("ch.epfl.scala"     % "sbt-scalafix"  % "0.14.7")
addSbtPlugin("rocks.earlyeffect" % "sbt-dynver-ci" % "0.2.2")
addSbtPlugin("com.github.sbt"    % "sbt-pgp"       % "2.3.1")
addSbtPlugin("org.scoverage"     % "sbt-scoverage" % "2.4.4")
addSbtPlugin("rocks.earlyeffect" % "sbt-zipx"      % "0.6.2")
addSbtPlugin("com.jamesward"     % "sbt-reload"    % "0.0.7")

// Workaround: sbt-scalafmt pulls in _2.13 variants via scalafmt-dynamic (for3Use2_13),
// conflicting with _3 variants from sbt-scalafix and sbt-scoverage in sbt 2.x.
excludeDependencies ++= Seq(
  ExclusionRule("org.scala-lang.modules", "scala-xml_2.13"),
  ExclusionRule("org.scala-lang.modules", "scala-collection-compat_2.13"),
  ExclusionRule("com.github.plokhotnyuk.jsoniter-scala", "jsoniter-scala-core_2.13"),
)

// jsdom-backed JS test environment (vendored in project/JSDOMNodeJSEnv.scala for sbt 2 / Scala 3).
// Requires `npm install` (jsdom) at the repo root before web/ JS tests run.
