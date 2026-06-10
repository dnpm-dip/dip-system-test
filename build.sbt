name         := "dip-integration-tests"
version      := "0.1.0"
scalaVersion := "2.13.18"

libraryDependencies ++= Seq(
  "org.scalatest"                 %% "scalatest"  % "3.2.19"  % Test,
  "com.softwaremill.sttp.client3" %% "core"       % "3.9.8"   % Test,
  "com.typesafe.play"             %% "play-json"  % "2.10.6"  % Test,
)

// Integration tests share a single Docker Compose stack: run sequentially in one JVM
Test / parallelExecution := false
Test / fork              := false
// Print test events as they happen; default buffering swallows all output when the
// process is killed before the run completes, making timeouts look like deadlocks.
Test / logBuffered       := false
