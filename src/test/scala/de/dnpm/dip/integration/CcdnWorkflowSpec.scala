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
    wiremock.resetRequests()
  }

  "CCDN workflow" should "confirm an MTB submission via the mock BfArM" in {
    val tan = uploadFakeMvhRecord(useCase = "mtb")

    // CCDN polls every 5 seconds; allow up to 60 s for the round-trip
    awaitReportStatus(tan, "Submitted", useCase = "mtb", timeoutMs = 60_000L)

    wiremock.requestCount(".*upload.*") should be >= 1
  }

  it should "confirm an RD submission via the mock BfArM" in {
    val tan = uploadFakeMvhRecord(useCase = "rd")

    awaitReportStatus(tan, "Submitted", useCase = "rd", timeoutMs = 60_000L)

    wiremock.requestCount(".*upload.*") should be >= 1
  }

  it should "confirm multiple uploads independently" in {
    val tans = (1 to 3).map(_ => uploadFakeMvhRecord(useCase = "mtb"))

    tans.foreach { tan =>
      awaitReportStatus(tan, "Submitted", useCase = "mtb", timeoutMs = 90_000L)
    }

    // Each upload causes one BfArM call
    wiremock.requestCount(".*upload.*") should be >= tans.size
  }

  it should "leave a report in Unsubmitted state when BfArM is unreachable" in {
    // This scenario is only observable when running without the test overlay's
    // mock-bfarm.  Skipped here; covered by the base compose (no BfArM creds).
    pending
  }

  it should "confirm an RD submission submitted to node2" in {
    val tan = uploadFakeMvhRecord(useCase = "rd", client = node2)

    awaitReportStatus(tan, "Submitted", useCase = "rd", client = node2, timeoutMs = 60_000L)
  }
}
