package de.dnpm.dip.integration

import play.api.libs.json._
import org.scalatest.BeforeAndAfterEach

/** Tests for the CCDN (zKDK) polling and BfArM submission workflow.
 *
 *  Flow under test:
 *    DIP node receives ETL upload
 *    → creates SubmissionReport with status=Unsubmitted
 *    → CCDN polls and discovers the new report (5-second interval in test config)
 *    → CCDN fetches full report, uploads to mock BfArM
 *    → CCDN calls :submitted back to DIP node
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
    awaitReportStatus(tan, "Submitted", useCase = "mtb", timeoutMs = 60_000L)

    bfarmWiremock.requestCount(".*upload.*") shouldBe 1
  }

  it should "confirm an RD submission via the mock BfArM" in {
    val tan = uploadFakeMvhRecordToDipnode(useCase = "rd")

    awaitReportStatus(tan, "Submitted", useCase = "rd", timeoutMs = 60_000L)

    bfarmWiremock.requestCount(".*upload.*") shouldBe 1
  }

  it should "confirm multiple uploads independently" in {
    val tans = (1 to 3).map(_ => uploadFakeMvhRecordToDipnode(useCase = "mtb"))

    tans.foreach { tan =>
      awaitReportStatus(tan, "Submitted", useCase = "mtb", timeoutMs = 90_000L)
    }

    // Each submission triggers exactly one BfArM call; beforeEach reset guarantees a clean baseline
    bfarmWiremock.requestCount(".*upload.*") shouldBe tans.size
  }

  it should "leave a report in Unsubmitted state when BfArM is unreachable" in {
    // Override the normal bfarm-upload stub with a higher-priority 503 fault
    val faultStub = Json.obj(
      "priority" -> 10,
      "request"  -> Json.obj("method" -> "POST", "urlPattern" -> ".*/upload.*"),
      "response" -> Json.obj("status" -> 503, "body" -> "Service Unavailable")
    )
    val stubId = bfarmWiremock.addStub(faultStub)
    try {
      val tan = uploadFakeMvhRecordToDipnode(useCase = "mtb")
      // Allow 3+ CCDN polling cycles (5 s each) to attempt and fail the upload
      Thread.sleep(20_000L)
      // Report must still be present in the unsubmitted queue
      awaitReportStatus(tan, "Unsubmitted", useCase = "mtb", timeoutMs = 5_000L)
      // CCDN must have attempted the upload at least once (exact count depends on timing)
      bfarmWiremock.requestCount(".*upload.*") should be >= 1
    } finally {
      bfarmWiremock.removeStub(stubId)
    }
  }

  it should "confirm an RD submission submitted to node2" in {
    val tan = uploadFakeMvhRecordToDipnode(useCase = "rd", client = node2)

    awaitReportStatus(tan, "Submitted", useCase = "rd", client = node2)

    bfarmWiremock.requestCount(".*upload.*") shouldBe 1
  }
}
