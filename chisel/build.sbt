ThisBuild / scalaVersion := "2.13.17"
ThisBuild / organization := "org.linxisa"

val chiselVersion = "7.3.0"

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
    )
  )
