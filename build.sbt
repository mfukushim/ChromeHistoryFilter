name := "ChromeHistoryFilter"

version := "0.1"

scalaVersion := "2.12.8"

scalacOptions += "-Ypartial-unification"

libraryDependencies ++= Seq("joda-time" % "joda-time" % "2.9.9",
  "org.typelevel" %% "cats-core" % "1.6.0",
  "com.h2database" % "h2" % "1.4.192",
  "org.scalatest" %% "scalatest" % "3.0.5" % "test",
  "com.github.tototoshi" %% "scala-csv" % "1.3.5",
  "com.typesafe.slick" %% "slick" % "3.3.2",
  "org.slf4j" % "slf4j-nop" % "1.7.26",
  "com.typesafe.slick" %% "slick-hikaricp" % "3.3.1",
  "com.github.scopt" %% "scopt" % "4.0.0-RC2",
  "org.xerial" % "sqlite-jdbc" % "3.28.0"
)
