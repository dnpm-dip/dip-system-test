package de.dnpm.dip.integration

import play.api.libs.json._
import org.scalatest.BeforeAndAfterEach
import scala.sys.process._

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
    awaitReportStatusInDipNode(tan, "Submitted", useCase = "mtb", timeoutMs = 60_000L)

    bfarmWiremock.requestCount(".*upload.*") shouldBe 1
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

    awaitReportStatusInDipNode(tan, "Submitted", useCase = "rd", client = node2)

    bfarmWiremock.requestCount(".*upload.*") shouldBe 1
  }

  it should "record available and unavailable DIP sites in mongoDB accordingly" in {
    // Query MongoDB via docker compose exec — no port needs to be exposed on the host.
    // Returns the count of documents matching the given Mongo filter expression.
    def mongoCount(filter: String): Int = {
      val out = Seq(
        "docker", "compose", "exec", "-T", "ccdn-mongodb",
        "mongosh", "--quiet",
        "--eval", s"print(db.siteAvailabilityReports.countDocuments($filter))",
        "ccdn"
      ).!!
      // mongosh --quiet may still emit ANSI escapes; extract the last numeric line.
      out.split("\\n").map(_.trim).filter(_.matches("\\d+")).lastOption
        .getOrElse(fail(s"No numeric output from mongosh for filter $filter; raw output: $out"))
        .toInt
    }

    // By the time this test runs the CCDN has been polling for minutes; both sites must
    // have at least one "fully" record.
    withClue("UKT (node1) should be recorded as fully available in MongoDB") {
      mongoCount("""{"site":"UKT","responsivity":"fully"}""") should be > 0
    }
    withClue("UKL (node2) should be recorded as fully available in MongoDB") {
      mongoCount("""{"site":"UKL","responsivity":"fully"}""") should be > 0
    }

    // Pause node1-backend to simulate UKT going offline.
    Seq("docker", "compose", "pause", "node1-backend").!
    try {
      // CCDN_BROKER_CONNECTOR_TIMEOUT is 10 s; add one more poll period (5 s) for margin.
      Thread.sleep(20_000L)

      withClue("UKT (node1) should be recorded as offline in MongoDB while paused") {
        mongoCount("""{"site":"UKT","responsivity":"offline"}""") should be > 0
      }
    } finally {
      Seq("docker", "compose", "unpause", "node1-backend").!
      // Wait until node1 responds again so it does not affect subsequent test suites.
      eventually(timeoutMs = 60_000L) {
        node1.get("/mtb/fake/data/patient-record").code.code should (be >= 200 and be < 300)
      }
    }
  }
}
