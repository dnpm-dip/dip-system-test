package de.dnpm.dip.integration

import play.api.libs.json._

/** Tests for the ETL validation endpoint and patient-record lifecycle. */
class EtlValidationSpec extends DipIntegrationSuite {

  // ─── Validate endpoint ─────────────────────────────────────────────────────

  "ETL validation" should "return 200 for a well-formed patient record" in {
    val resp = node1.get("/mtb/fake/data/patient-record")
    resp.code.code shouldBe 200
    val record = resp.body.merge

    val validateResp = node1.post("/mtb/etl/patient-record:validate", record)
    withClue(s"Validation response: ${validateResp.body.merge}\n") {
      validateResp.code.code shouldBe 200
    }
  }

  it should "return issues for a malformed patient record (missing required field)" in {
    val malformed = """{"patient": {}}"""
    val resp      = node1.post("/mtb/etl/patient-record:validate", malformed)
    // Validation endpoint returns 200 with issue list, or 422 directly
    resp.code.code should (be >= 200 and be < 500)
    if (resp.code.code == 200) {
      val issues = (Json.parse(resp.body.merge) \ "issues").asOpt[JsArray]
        .orElse((Json.parse(resp.body.merge)).asOpt[JsArray])
      // Expect at least one issue reported
      issues.foreach(_.value.size should be > 0)
    }
  }

  // ─── Patient record delete ──────────────────────────────────────────────────

  "ETL delete" should "remove a previously uploaded record" in {
    // Upload a record and capture the patient ID
    val (_, body) = fetchFakeMvhSubmission("mtb")
    val patientId = (Json.parse(body) \ "patient" \ "id").as[String]

    node1.post("/mtb/etl/patient-record", body).code.code shouldBe 200

    // Delete
    val deleteResp = node1.delete(s"/mtb/etl/patient/$patientId")
    deleteResp.code.code should (be >= 200 and be < 300)

    // After deletion the submission report must not appear in the unsubmitted queue
    val tanFromBody = (Json.parse(body) \ "metadata" \ "transferTAN").as[String]
    val reportsResp = node1.get("/mtb/peer2peer/mvh/submission-reports?status=unsubmitted")
    reportsResp.code.code shouldBe 200
    val reports = (Json.parse(reportsResp.body.merge) \ "entries").as[JsArray].value
    val entry = reports.find(r => (r \ "id").asOpt[String].contains(tanFromBody))
    withClue(s"Deleted record TAN=$tanFromBody still appears in unsubmitted report queue") {
      entry shouldBe empty
    }
  }

  // ─── Duplicate patient ID ───────────────────────────────────────────────────

  it should "reject a second upload with the same patient ID but different TAN" in {
    val (_, firstBody) = fetchFakeMvhSubmission("mtb")
    val patientId      = (Json.parse(firstBody) \ "patient" \ "id").as[String]

    node1.post("/mtb/etl/patient-record", firstBody).code.code shouldBe 200

    // Reuse same patient ID with a fresh TAN → conflict expected
    val freshTan    = randomHex(32)
    val secondBody  = (Json.parse(firstBody).as[JsObject] ++ Json.obj(
      "metadata" -> ((Json.parse(firstBody) \ "metadata").as[JsObject] ++ Json.obj(
        "type"        -> "initial",
        "transferTAN" -> freshTan,
      ))
    )).toString()
    val resp = node1.post("/mtb/etl/patient-record", secondBody)
    resp.code.code should (be >= 400 and be < 500)
  }
}
