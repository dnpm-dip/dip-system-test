package de.dnpm.dip.integration

import scala.sys.process._
import scala.util.Try

/** Manages the Docker Compose stack for all integration tests.
 *
 *  `ready` is a lazy val; the first spec that references it starts the stack
 *  and registers a JVM-shutdown hook to tear it down.  Subsequent specs hit
 *  the already-running environment.
 *
 *  Run the tests from the project root so the relative compose-file paths
 *  resolve correctly:  sbt test
 */
object DipTestEnvironment {

  val node1Url    = "http://localhost:8001"
  val node2Url    = "http://localhost:8002"
  val brokerUrl   = "http://localhost:19010"
  val wiremockUrl = "http://localhost:18080"
  val authup1Url  = "http://localhost:8011"
  val authup2Url  = "http://localhost:8012"

  private val composeFiles = Seq(
    "-f", "docker-compose.yml",
    "-f", "docker-compose.test.yml",
  )

  lazy val ready: Boolean = {
    startStack()
    waitForHealthy()
    true
  }

  private def startStack(): Unit = {
    println("[DipTestEnvironment] Starting Docker Compose stack…")
    val code = (Seq("docker", "compose") ++ composeFiles ++ Seq("up", "-d")).!
    require(code == 0, s"docker compose up failed (exit $code)")

    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      println("[DipTestEnvironment] Tearing down Docker Compose stack…")
      (Seq("docker", "compose") ++ composeFiles ++ Seq("down")).!
    }))
  }

  private def waitForHealthy(): Unit = {
    val checks = List(
      node1Url    -> s"$node1Url/api/mtb/fake/data/patient-record",
      node2Url    -> s"$node2Url/api/rd/fake/data/patient-record",
      wiremockUrl -> s"$wiremockUrl/__admin/health",
    )
    checks.foreach { case (name, url) =>
      println(s"[DipTestEnvironment] Waiting for $name …")
      awaitUrl(url, timeoutSec = 300)
    }
    println("[DipTestEnvironment] All services ready.")
  }

  private def awaitUrl(url: String, timeoutSec: Int): Unit = {
    val deadline = System.currentTimeMillis() + timeoutSec * 1000L
    var lastMsg  = "never attempted"
    while (System.currentTimeMillis() < deadline) {
      Try {
        val conn = new java.net.URL(url).openConnection().asInstanceOf[java.net.HttpURLConnection]
        conn.setConnectTimeout(3_000)
        conn.setReadTimeout(5_000)
        try { conn.connect(); conn.getResponseCode } finally conn.disconnect()
      } match {
        case scala.util.Success(c) if c < 500 => return
        case scala.util.Success(c)             => lastMsg = s"HTTP $c"
        case scala.util.Failure(ex)            => lastMsg = ex.getMessage
      }
      Thread.sleep(3_000)
    }
    throw new RuntimeException(s"$url not ready after ${timeoutSec}s: $lastMsg")
  }
}
