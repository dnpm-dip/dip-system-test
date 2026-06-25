package de.dnpm.dip.integration.support

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

  val node1    = new DipNodeClient(DipTestEnvironment.dipNode1Url)
  val node2    = new DipNodeClient(DipTestEnvironment.dipNode2Url)
  val broker   = new DipNodeClient(DipTestEnvironment.brokerUrl)
  val bfarmWiremock = new WiremockClient(DipTestEnvironment.bfarmWiremockUrl)

  // ─── Polling helper ────────────────────────────────────────────────────────

  def eventually[A](timeoutMs: Long, intervalMs: Long = 2_000L)(f: => A): A = {
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

  /** Load a static MVH submission, randomise all identity fields, return (tan, body). */
  def generateFakeMvhSubmission(useCase: String, intendedTan:String=randomHex()): (String, String) = {
    require(Set("mtb","rd").contains(useCase))
    val raw  = scala.io.Source.fromResource(s"submissions/$useCase.json").mkString
    val json = Json.parse(raw)

    val oldPatientId = (json \ "patient" \ "id").as[String]
    val oldEpisodeIds = (json \ "episodesOfCare").as[JsArray].value
                          .map(e => (e \ "id").as[String])

    var body = raw
    body = body.replace(oldPatientId, java.util.UUID.randomUUID().toString)
    for (eid <- oldEpisodeIds)
      body = body.replace(eid, java.util.UUID.randomUUID().toString)

    val parsed  = Json.parse(body).as[JsObject]
    val withTan = parsed ++ Json.obj(
      "metadata" -> ((parsed \ "metadata").as[JsObject] ++ Json.obj("transferTAN" -> intendedTan))
    )
    (intendedTan, withTan.toString())
  }

  /** Upload a fake MVH submission, assert 200, return the TAN. */
  def uploadFakeMvhRecordToDipnode(useCase: String, client: DipNodeClient = node1, intendedTan:String=randomHex()): String = {
    require(Set("mtb","rd").contains(useCase))
    val (tan, fakeSubmissionBody) = generateFakeMvhSubmission(useCase,intendedTan)
    val resp        = client.post(s"/$useCase/etl/patient-record", fakeSubmissionBody)
    withClue(s"Upload of $useCase record to ${client.baseUrl} returned ${resp.code}: ${resp.body.merge}\n") {
      resp.code.code shouldBe 200
    }
    tan
  }

  /** Wait until every node's unsubmitted queue is empty (CCDN has processed all pre-existing
   *  reports), then reset the BfArM wiremock request log.  Call in beforeEach of specs that
   *  assert exact BfArM upload counts so records from previous tests don't pollute the baseline.
   */
  def drainUnsubmittedQueuesAndResetCounters(timeoutMs: Long = 120_000L): Unit = {
    // node1 (UKT) exports MTB + RD; node2 (UKL) exports RD only
    for ((client, useCase) <- List(node1 -> "mtb", node1 -> "rd", node2 -> "rd")) {
      eventually(timeoutMs = timeoutMs) {
        val resp = client.get(s"/$useCase/peer2peer/mvh/submission-reports?status=unsubmitted")
        resp.code.code shouldBe 200
        val body    = resp.body.getOrElse(fail("Unexpected error body"))
        val entries = (Json.parse(body) \ "entries").as[JsArray].value
        withClue(s"${useCase.toUpperCase} unsubmitted queue on ${client.baseUrl} still has ${entries.size} entries") {
          entries shouldBe empty
        }
      }
    }
    bfarmWiremock.resetRequests()
  }

  /** Poll the MVH submission-report list (filtered to Unsubmitted) until the entry for `tan` reaches `expectedStatus`.
   *
   *  Uses GET /$useCase/peer2peer/mvh/submission-reports?status=unsubmitted, which is the same
   *  endpoint the CCDN polls.  After the CCDN calls :submitted, the entry disappears from this
   *  list.  TAN is carried in the top-level "id" field of each Submission.Report entry.
   */
  def awaitReportStatusInDipNode(
    tan: String,
    expectedStatus: String,
    useCase: String,
    client: DipNodeClient = node1,
    timeoutMs: Long
  ): Unit = {
    require(Set("mtb","rd").contains(useCase))
    eventually(timeoutMs = timeoutMs) {
      val resp = client.get(s"/$useCase/peer2peer/mvh/submission-reports?status=unsubmitted")
      resp.code.code shouldBe 200
      val body    = resp.body.getOrElse(fail("Unexpected error body"))
      val entries = (Json.parse(body) \ "entries").as[JsArray].value
      val entry   = entries.find(r => (r \ "id").asOpt[String].contains(tan))
      expectedStatus match {
        case "Submitted" =>
          withClue(s"TAN=$tan still present in unsubmitted report queue after ${timeoutMs}ms") {
            entry shouldBe empty
          }
        case _ =>
          withClue(s"No unsubmitted report with TAN=$tan in ${entries.size} entries") {
            entry shouldBe defined
          }
      }
    }
  }
}
