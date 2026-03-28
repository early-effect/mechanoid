addSbtPlugin("org.scalameta"  % "sbt-scalafmt" % "2.5.6")
addSbtPlugin("org.scalameta"  % "sbt-mdoc"     % "2.8.2")
addSbtPlugin("com.eed3si9n"   % "sbt-assembly" % "2.3.1")
addSbtPlugin("ch.epfl.scala"  % "sbt-scalafix" % "0.14.6")
addSbtPlugin("com.github.sbt" % "sbt-dynver"   % "5.1.1")
addSbtPlugin("com.github.sbt" % "sbt-pgp"      % "2.3.1")

addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.4.4")

// Workaround: sbt-scalafmt pulls in _2.13 variants via scalafmt-dynamic (for3Use2_13),
// conflicting with _3 variants from sbt-scalafix and sbt-scoverage in sbt 2.x.
excludeDependencies ++= Seq(
  ExclusionRule("org.scala-lang.modules", "scala-xml_2.13"),
  ExclusionRule("org.scala-lang.modules", "scala-collection-compat_2.13"),
  ExclusionRule("com.github.plokhotnyuk.jsoniter-scala", "jsoniter-scala-core_2.13"),
)

// val overrideSemanticDbVersion = "4.14.5"

// // Override semanticdb version for Metals 2.0.0-M2 compatibility
// dependencyOverrides ++= Seq(
//   "org.scalameta" % "semanticdb-scalac_2.12.18" % overrideSemanticDbVersion,
//   "org.scalameta" % "semanticdb-scalac_2.12.19" % overrideSemanticDbVersion,
//   "org.scalameta" % "semanticdb-scalac_2.12.20" % overrideSemanticDbVersion,
//   "org.scalameta" % "semanticdb-scalac_2.12.21" % overrideSemanticDbVersion,
// )
