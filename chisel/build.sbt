ThisBuild / scalaVersion := "2.13.17"
ThisBuild / organization := "org.linxisa"

val chiselVersion = "7.3.0"
val chiselTestJobs = sys.env.get("LINX_CHISEL_TEST_JOBS") match {
  case None => 2
  case Some(text) =>
    text.toIntOption.filter(_ > 0).getOrElse(
      sys.error("LINX_CHISEL_TEST_JOBS must be a positive integer"))
}

lazy val linxcore = (project in file("."))
  .settings(
    name := "linxcore-chisel",
    libraryDependencies ++= Seq(
      "org.chipsalliance" %% "chisel" % chiselVersion,
      "org.scalatest" %% "scalatest" % "3.2.19" % Test
    ),
    addCompilerPlugin(
      "org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full
    ),
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Xfatal-warnings"
    ),
    Test / parallelExecution := true,
    Global / concurrentRestrictions += Tags.limit(Tags.Test, chiselTestJobs)
  )
