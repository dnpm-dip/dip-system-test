package de.dnpm.dip.integration

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json._
import scala.util.Try

/** Base trait for all DIP integration specs.
 *
 *  Triggers the shared Docker Compose stack on first use, and provides:
 *  - Pre-configured clients for node1, node2, wiremock, and the broker.
 *  - `eventually` for polling assertions with a configurable timeout.
 *  - `randomHex` for generating unique TANs.
 *  - `uploadFakeMvhRecord` convenience that fetches a fake payload and POSTs it.
 */
trait DipIntegrationSuite extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  override def beforeAll(): Unit = {
    super.beforeAll()
    val _ = DipTestEnvironment.ready
  }

  val node1    = new DipNodeClient(DipTestEnvironment.node1Url)
  val node2    = new DipNodeClient(DipTestEnvironment.node2Url)
  val broker   = new DipNodeClient(DipTestEnvironment.brokerUrl)
  val wiremock = new WiremockClient(DipTestEnvironment.wiremockUrl)

  // ─── Polling helper ────────────────────────────────────────────────────────

  def eventually[A](timeoutMs: Long = 60_000L, intervalMs: Long = 2_000L)(f: => A): A = {
    val deadline = System.currentTimeMillis() + timeoutMs
    var lastEx: Throwable = new RuntimeException("eventually: never attempted")
    while (System.currentTimeMillis() < deadline) {
      Try(f) match {
        case scala.util.Success(v) => return v
        case scala.util.Failure(e) => lastEx = e
      }
      Thread.sleep(intervalMs)
    }
    throw new RuntimeException(s"eventually timed out after ${timeoutMs}ms: ${lastEx.getMessage}", lastEx)
  }

  // ─── Data helpers ──────────────────────────────────────────────────────────

  def randomHex(nBytes: Int = 32): String = {
    val buf = new Array[Byte](nBytes)
    new java.security.SecureRandom().nextBytes(buf)
    buf.map("%02x".format(_)).mkString
  }

  /** Fetch a fake MVH submission payload from the node, return (tan, fullBody). */
  def fetchFakeMvhSubmission(useCase: String = "mtb", client: DipNodeClient = node1): (String, String) = {
    val resp = client.get(s"/api/$useCase/fake/data/mvh-submission")
    resp.code.code shouldBe 200
    val body = resp.body.merge
    val tan  = (Json.parse(body) \ "metadata" \ "transferTAN").as[String]
    (tan, body)
  }

  /** Upload a fake MVH submission, assert 201, return the TAN. */
  def uploadFakeMvhRecord(useCase: String = "mtb", client: DipNodeClient = node1): String = {
    val (tan, body) = fetchFakeMvhSubmission(useCase, client)
    val resp        = client.post(s"/api/$useCase/etl/patient-record", body)
    withClue(s"Upload of $useCase record to ${client.baseUrl} returned ${resp.code}: ${resp.body.merge}\n") {
      resp.code.code shouldBe 201
    }
    tan
  }

  /** Poll the MVH submission report list until the entry for `tan` has `expectedStatus`. */
  def awaitReportStatus(
    tan: String,
    expectedStatus: String,
    useCase: String     = "mtb",
    client: DipNodeClient = node1,
    timeoutMs: Long     = 60_000L,
  ): Unit = {
    eventually(timeoutMs = timeoutMs) {
      val resp = client.get(s"/api/$useCase/peer2peer/mvh/submission/report")
      resp.code.code shouldBe 200
      val reports = Json.parse(resp.body.merge).as[JsArray].value
      val entry   = reports.find(r => (r \ "transferTAN").asOpt[String].contains(tan))
      withClue(s"No report with TAN=$tan in ${reports.size} entries") {
        entry shouldBe defined
      }
      withClue(s"Report status for TAN=$tan") {
        (entry.get \ "status").as[String] shouldBe expectedStatus
      }
    }
  }
}
