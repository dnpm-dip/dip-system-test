package de.dnpm.dip.integration.specs

import play.api.libs.json._
import org.scalatest.BeforeAndAfterEach
import com.mongodb.client.MongoClients
import de.dnpm.dip.integration.support.{DipIntegrationSuite, DockerCompose}
import org.bson.Document

/** Tests for the CCDN (zKDK) polling and BfArM submission workflow.
 *
 *  The test environment mirrors the production split: two separate zKDK instances,
 *  one per use case, sharing a single MongoDB for convenience (production uses separate DBs).
 *    ccdn-mtb  — polls UK1 for MTB submissions only
 *    ccdn-rd   — polls UK1 + UK2 for RD submissions only
 *
 *  Flow under test:
 *    DIP node receives ETL upload
 *    → creates SubmissionReport with status=Unsubmitted
 *    → responsible zKDK polls and discovers the new report (5-second interval in test config)
 *    → zKDK fetches full report, uploads to mock BfArM
 *    → zKDK calls :submitted back to DIP node
 *    → DIP node updates report status to Submitted
 */
class CcdnWorkflowSpec extends DipIntegrationSuite with BeforeAndAfterEach {

  override def beforeEach(): Unit = {
    super.beforeEach()
    drainUnsubmittedQueuesAndResetCounters()
  }

  "CCDN workflow" should "confirm an MTB submission via the mock BfArM" in {
    val tan = uploadFakeMvhRecordToDipnode(useCase = "mtb")

    // CCDN polls every 5 seconds; allow up to 60 s for the round-trip
    awaitReportStatusInDipNode(tan, "Submitted", useCase = "mtb", timeoutMs = 60_000L)

    bfarmWiremock.requestCount(".*upload.*") shouldBe 1

    // Counter: the request body sent to BfArM must contain the TAN of the record we uploaded,
    // proving CCDN forwarded actual data rather than a hardcoded or empty payload.
    val uploadRequests = bfarmWiremock.allRequests().value
      .filter(r => (r \ "request" \ "url").asOpt[String].exists(_.contains("upload")))
    val body = uploadRequests.headOption
      .flatMap(r => (r \ "request" \ "body").asOpt[String]
        .orElse((r \ "request" \ "bodyAsBase64").asOpt[String]
          .map(b64 => new String(java.util.Base64.getDecoder.decode(b64)))))
      .getOrElse(fail("No body found in BfArM upload request"))
    withClue(s"BfArM upload body should contain TAN=$tan") {
      body should include(tan)
    }
  }

  it should "confirm an RD submission via the mock BfArM" in {
    val tan = uploadFakeMvhRecordToDipnode(useCase = "rd")

    awaitReportStatusInDipNode(tan, "Submitted", useCase = "rd", timeoutMs = 60_000L)

    bfarmWiremock.requestCount(".*upload.*") shouldBe 1
  }

  it should "confirm multiple uploads independently" in {
    val tans = (1 to 3).map(_ => uploadFakeMvhRecordToDipnode(useCase = "mtb"))

    tans.foreach { tan =>
      awaitReportStatusInDipNode(tan, "Submitted", useCase = "mtb", timeoutMs = 90_000L)
    }

    // Each submission triggers exactly one BfArM call; beforeEach reset guarantees a clean baseline
    bfarmWiremock.requestCount(".*upload.*") shouldBe tans.size
  }

  it should "leave a report in Unsubmitted state when BfArM is unreachable" in {
    // Override the normal bfarm-upload stub with a higher-priority 503 fault
    val faultStub = Json.obj(
      "priority" -> 1,
      "request"  -> Json.obj("method" -> "POST", "urlPattern" -> ".*/upload.*"),
      "response" -> Json.obj("status" -> 503, "body" -> "Service Unavailable")
    )
    val stubId = bfarmWiremock.addStub(faultStub)
    try {
      val testTan:String = randomHex().replaceFirst("........","AAAAAAAA")
      uploadFakeMvhRecordToDipnode(useCase = "mtb", intendedTan = testTan) shouldBe testTan
      // Wait for a polling cycle to complete
      Thread.sleep(6_000L)
      // Report must still be present in the unsubmitted queue
      awaitReportStatusInDipNode(testTan, "Unsubmitted", useCase = "mtb", timeoutMs = 5_000L)
      // CCDN must have attempted the upload at least once (exact count depends on timing)
      // if this should fail, first attempt should be increasing the sleep above
      bfarmWiremock.requestCount(".*upload.*") should be >= 1
    } finally {
      bfarmWiremock.removeStub(stubId)
    }
  }

  it should "confirm an RD submission submitted to node2" in {
    val tan = uploadFakeMvhRecordToDipnode(useCase = "rd", client = node2)

    awaitReportStatusInDipNode(tan, "Submitted", useCase = "rd", client = node2, 60_000L)

    bfarmWiremock.requestCount(".*upload.*") shouldBe 1
  }

  it should "only process its own use case when the other zKDK is offline" in {
    // Pause the MTB zKDK so only the RD zKDK is running.
    DockerCompose.pauseService("ccdn-mtb")
    var mtbTan: Option[String] = None
    try {
      mtbTan    = Some(uploadFakeMvhRecordToDipnode(useCase = "mtb"))
      val rdTan = uploadFakeMvhRecordToDipnode(useCase = "rd")

      // The RD zKDK must process the RD submission normally.
      awaitReportStatusInDipNode(rdTan, "Submitted", useCase = "rd", timeoutMs = 60_000L)
      bfarmWiremock.requestCount(".*upload.*") shouldBe 1

      // The MTB submission must remain untouched while ccdn-mtb is paused.
      // By now at least one RD polling cycle has completed, so enough time has elapsed.
      awaitReportStatusInDipNode(mtbTan.get, "Unsubmitted", useCase = "mtb", timeoutMs = 5_000L)
    } finally {
      DockerCompose.unpauseService("ccdn-mtb")
      // After recovery ccdn-mtb must pick up and submit the pending MTB report.
      mtbTan.foreach(awaitReportStatusInDipNode(_, "Submitted", useCase = "mtb", timeoutMs = 60_000L))
    }
  }

  it should "record available and unavailable DIP sites in mongoDB accordingly" in {
    def mongoCount(filter: String): Int = {
      val client = MongoClients.create("mongodb://localhost:27017")
      try {
        client.getDatabase("ccdn")
          .getCollection("siteAvailabilityReports")
          .countDocuments(Document.parse(filter))
          .toInt
      } finally {
        client.close()
      }
    }

    // By the time this test runs the CCDN has been polling for minutes; both sites must
    // have at least one "fully" record.
    withClue("UK1 (node1) should be recorded as fully available in MongoDB") {
      mongoCount("""{"site":"UK1","responsivity":"fully"}""") should be > 0
    }
    withClue("UK2 (node2) should be recorded as fully available in MongoDB") {
      mongoCount("""{"site":"UK2","responsivity":"fully"}""") should be > 0
    }

    // Pause node1-backend to simulate UK1 going offline.
    DockerCompose.pauseService("node1-backend")
    try {
      // CCDN_BROKER_CONNECTOR_TIMEOUT is 10 s; add one more poll period (5 s) for margin.
      Thread.sleep(20_000L)

      withClue("UK1 (node1) should be recorded as offline in MongoDB while paused") {
        mongoCount("""{"site":"UK1","responsivity":"offline"}""") should be > 0
      }
    } finally {
      DockerCompose.unpauseService("node1-backend")
      // Wait until node1 responds again so it does not affect subsequent test suites.
      eventually(timeoutMs = 60_000L) {
        node1.get("/mtb/fake/data/patient-record").code.code should (be >= 200 and be < 300)
      }
    }
  }
}
