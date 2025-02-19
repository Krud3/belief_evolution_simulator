ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.6.1"
ThisBuild / fork := true

// JVM options
javaOptions ++= Seq(
    "-Xmx32g",
    "--add-modules=jdk.incubator.vector"
    )

lazy val root = (project in file("."))
  .settings(
      name := "extended_model",
      // Add scalac options
      scalacOptions ++= Seq(
          "-Yimports:java.lang,scala,scala.Predef,scala.util.chaining,jdk.incubator.vector"
          ),
      // Force forking again at the project level
      run / fork := true,
      // Explicitly add the module to compilation
      compile / javacOptions ++= Seq("--add-modules", "jdk.incubator.vector")
      )
// scalacOptions += "-Yimports:java.lang,scala,scala.Predef,scala.util.chaining,jdk.incubator.vector"

resolvers += "Akka library repository".at("https://repo.akka.io/maven")
val AkkaVersion = "2.6.21" // 2.10.0
libraryDependencies += "com.typesafe.akka" %% "akka-actor" % AkkaVersion
libraryDependencies += "com.typesafe.akka" %% "akka-remote" % AkkaVersion
libraryDependencies += "io.spray" %%  "spray-json" % "1.3.6"
libraryDependencies += "org.postgresql" % "postgresql" % "42.7.4"
libraryDependencies += "com.zaxxer" % "HikariCP" % "5.1.0"
libraryDependencies += "tech.ant8e" %% "uuid4cats-effect" % "0.5.0"
libraryDependencies += "com.typesafe.play" %% "play-json" % "2.10.5"
