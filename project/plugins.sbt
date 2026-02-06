addSbtPlugin("org.scalameta"  % "sbt-scalafmt"  % "2.5.6")
addSbtPlugin("org.scalameta"  % "sbt-mdoc"      % "2.8.2")
addSbtPlugin("com.eed3si9n"   % "sbt-assembly"  % "2.3.1")
addSbtPlugin("ch.epfl.scala"  % "sbt-scalafix"  % "0.14.5")
addSbtPlugin("com.github.sbt" % "sbt-dynver"    % "5.1.1")
addSbtPlugin("com.github.sbt" % "sbt-pgp"       % "2.3.2-M1")
addSbtPlugin("org.xerial.sbt" % "sbt-sonatype"  % "3.12.2")
addSbtPlugin("org.scoverage"  % "sbt-scoverage" % "2.4.4")

// val overrideSemanticDbVersion = "4.14.5"

// // Override semanticdb version for Metals 2.0.0-M2 compatibility
// dependencyOverrides ++= Seq(
//   "org.scalameta" % "semanticdb-scalac_2.12.18" % overrideSemanticDbVersion,
//   "org.scalameta" % "semanticdb-scalac_2.12.19" % overrideSemanticDbVersion,
//   "org.scalameta" % "semanticdb-scalac_2.12.20" % overrideSemanticDbVersion,
//   "org.scalameta" % "semanticdb-scalac_2.12.21" % overrideSemanticDbVersion,
// )
