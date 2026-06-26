package de.dnpm.dip.integration.support

import scala.util.Try
import com.mongodb.client.MongoClients
import org.bson.Document

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

  val dipNode1Url    = "http://localhost:8001"
  val dipNode2Url    = "http://localhost:8002"
  val brokerUrl   = "http://localhost:19010"
  val bfarmWiremockUrl = "http://localhost:18080"
  val dipAuthup1Url  = "http://localhost:8011"
  val dipAuthup2Url  = "http://localhost:8012"

  lazy val ready: Boolean = {
    startStack()
    waitForHealthy()
    true
  }

  private def startStack(): Unit = {
    println("[DipTestEnvironment] Starting Docker Compose stack…")
    DockerCompose.up()

    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      println("[DipTestEnvironment] Tearing down Docker Compose stack…")
      DockerCompose.down()
    }))
  }

  private def waitForHealthy(): Unit = {
    val checks = List(
      dipNode1Url    -> s"$dipNode1Url/mtb/fake/data/patient-record",
      dipNode2Url    -> s"$dipNode2Url/rd/fake/data/patient-record",
      bfarmWiremockUrl -> s"$bfarmWiremockUrl/__admin/health",
    )
    checks.foreach { case (name, url) =>
      println(s"[DipTestEnvironment] Waiting for $name …")
      awaitUrl(url, timeoutSec = 300)
    }
    waitForCcdnReady()
    println("[DipTestEnvironment] All services ready.")
  }

  // Polls MongoDB until both CCDN instances have written at least one successful availability
  // report, proving each completed a full workflow cycle without crashing.
  private def waitForCcdnReady(): Unit = {
    println("[DipTestEnvironment] Waiting for CCDN to complete first workflow cycle…")
    val deadline = System.currentTimeMillis() + 120_000L
    var ready = false
    while (System.currentTimeMillis() < deadline && !ready) {
      Try {
        val client = MongoClients.create("mongodb://localhost:27017")
        try {
          val coll = client.getDatabase("ccdn").getCollection("siteAvailabilityReports")
          // UK1 is monitored by ccdn-mtb and ccdn-rd; UK2 only by ccdn-rd.
          // "fully" is Responsivity.success serialised by the CCDN.
          val uk1 = coll.countDocuments(Document.parse("""{"site":"UK1","responsivity":"fully"}"""))
          val uk2 = coll.countDocuments(Document.parse("""{"site":"UK2","responsivity":"fully"}"""))
          uk1 > 0 && uk2 > 0
        } finally client.close()
      } match {
        case scala.util.Success(true)  => ready = true
        case scala.util.Success(false) => println("  [wait] CCDN not ready yet…"); Thread.sleep(3_000)
        case scala.util.Failure(ex)    => println(s"  [wait] MongoDB check failed: ${ex.getMessage}"); Thread.sleep(3_000)
      }
    }
    if (!ready) throw new RuntimeException("CCDN did not complete a workflow cycle within 120s")
    println("[DipTestEnvironment] CCDN ready.")
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
        case scala.util.Success(c) if c < 400 => return
        case scala.util.Success(c)             => lastMsg = s"HTTP $c"; println(s"  [wait] $url → $lastMsg")
        case scala.util.Failure(ex)            => lastMsg = ex.getMessage; println(s"  [wait] $url → $lastMsg")
      }
      Thread.sleep(3_000)
    }
    throw new RuntimeException(s"$url not ready after ${timeoutSec}s: $lastMsg")
  }
}
