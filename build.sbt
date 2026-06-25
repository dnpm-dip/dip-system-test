name         := "dip-integration-tests"
version      := "0.1.0"
scalaVersion := "2.13.18"

libraryDependencies ++= Seq(
  "org.scalatest"                 %% "scalatest"            % "3.2.19"  % Test,
  "com.softwaremill.sttp.client3" %% "core"                 % "3.9.8"   % Test,
  "com.typesafe.play"             %% "play-json"            % "2.10.6"  % Test,
  "org.mongodb"                    % "mongodb-driver-sync"  % "5.3.0"   % Test,
  "org.slf4j"                      % "slf4j-nop"            % "1.7.36"  % Test, // to stop a warning about slf4j not being installed
)

// Integration tests share a single Docker Compose stack: run sequentially in one JVM
Test / parallelExecution := false
Test / fork              := true
// Print test events as they happen; default buffering swallows all output when the
// process is killed before the run completes, making timeouts look like deadlocks.
Test / logBuffered       := false
// Run only the top-level wrapper so individual specs are not discovered and double-run.
Test / testOptions += Tests.Filter(_ == "de.dnpm.dip.integration.specs.IntegrationTests")
