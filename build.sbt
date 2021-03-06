
lazy val `kernel-api` = project.in(file("api"))
  .settings(commonSettings)

lazy val kernel = project.in(file("core"))
  .dependsOn(`kernel-api`)
  .settings(commonSettings)
  .settings(testSettings)
  .settings(
    libraryDependencies ++= Seq(
      "com.typesafe" % "config" % "1.2.1",
      "com.github.alexarchambault" %% "argonaut-shapeless_6.1" % "1.0.0-RC1",
      "org.zeromq" % "jeromq" % "0.3.4",
      "com.typesafe.scala-logging" %% "scala-logging-slf4j" % "2.1.2",
      "org.scalaz.stream" %% "scalaz-stream" % "0.6a"
    ),
    publishArtifact in (Test, packageBin) := true,
    publishArtifact in (Test, packageSrc) := true
  )

lazy val `kernel-cli` = project.in(file("cli"))
  .dependsOn(kernel)
  .settings(commonSettings)
  .settings(packAutoSettings)
  .settings(
    libraryDependencies ++= Seq(
      "com.github.alexarchambault" %% "case-app" % "1.0.0-RC2",
      "ch.qos.logback" % "logback-classic" % "1.1.7"
    )
  )

lazy val `jupyter-kernel` = project.in(file("."))
  .settings(commonSettings)
  .settings(noPublishSettings)
  .aggregate(`kernel-api`, kernel, `kernel-cli`)


lazy val commonSettings = Seq(
  organization := "com.github.alexarchambault.jupyter",
  scalaVersion := "2.11.8",
  scalacOptions ++= Seq("-deprecation", "-unchecked", "-feature"),
  resolvers ++= Seq(
    Resolver.sonatypeRepo("releases"),
    "Scalaz Bintray Repo" at "http://dl.bintray.com/scalaz/releases"
  ),
  libraryDependencies ++= {
    if (scalaBinaryVersion.value == "2.10") Seq(
      compilerPlugin("org.scalamacros" % "paradise" % "2.0.1" cross CrossVersion.full)
    ) else Nil
  }
) ++ publishSettings

lazy val testSettings = Seq(
  libraryDependencies += "com.lihaoyi" %% "utest" % "0.3.0" % "test",
  testFrameworks += new TestFramework("utest.runner.Framework")
)

lazy val publishSettings = Seq(
  publishMavenStyle := true,
  publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (isSnapshot.value)
      Some("snapshots" at nexus + "content/repositories/snapshots")
    else
      Some("releases"  at nexus + "service/local/staging/deploy/maven2")
  },
  licenses := Seq("LGPL-3.0" -> url("http://www.gnu.org/licenses/lgpl.txt")),
  scmInfo := Some(ScmInfo(
    url("https://github.com/alexarchambault/jupyter-kernel"),
    "git@github.com:alexarchambault/jupyter-kernel.git"
  )),
  homepage := Some(url("https://github.com/alexarchambault/jupyter-kernel")),
  pomExtra := {
    <developers>
      <developer>
        <id>alexarchambault</id>
        <name>Alexandre Archambault</name>
        <url>https://github.com/alexarchambault</url>
      </developer>
    </developers>
  },
  credentials += {
    Seq("SONATYPE_USER", "SONATYPE_PASS").map(sys.env.get) match {
      case Seq(Some(user), Some(pass)) =>
        Credentials("Sonatype Nexus Repository Manager", "oss.sonatype.org", user, pass)
      case _ =>
        Credentials(Path.userHome / ".ivy2" / ".credentials")
    }
  },
  scalacOptions += "-target:jvm-1.7"
)

lazy val noPublishSettings = Seq(
  publish := (),
  publishLocal := (),
  publishArtifact := false
)
