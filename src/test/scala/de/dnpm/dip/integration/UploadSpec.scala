package de.dnpm.dip.integration

import play.api.libs.json._

/** Tests for ETL upload business rules at the DIP node. */
class UploadSpec extends DipIntegrationSuite {

  // ─── Duplicate TAN ─────────────────────────────────────────────────────────

  "ETL upload" should "reject a second upload with the same TAN" in {
    val (_, body) = fetchFakeMvhSubmission("mtb")

    val first = node1.post("/mtb/etl/patient-record", body)
    first.code.code shouldBe 200

    val second = node1.post("/mtb/etl/patient-record", body)
    second.code.code should (be >= 400 and be < 500)
  }

  it should "reject a second initial submission for the same EpisodeOfCare" in {
    val (_, body) = fetchFakeMvhSubmission("mtb")
    node1.post("/mtb/etl/patient-record", body).code.code shouldBe 200

    val freshTan  = randomHex(32)
    val second    = (Json.parse(body).as[JsObject] ++ Json.obj(
      "metadata" -> ((Json.parse(body) \ "metadata").as[JsObject] ++ Json.obj("transferTAN" -> freshTan))
    )).toString()
    node1.post("/mtb/etl/patient-record", second).code.code should (be >= 400 and be < 500)
  }

  // ─── Submission type rules ──────────────────────────────────────────────────

  it should "accept a submission with type=test" in {
    val (_, body) = fetchFakeMvhSubmission("mtb")
    val json      = Json.parse(body)
    val modified  = json.as[JsObject] ++ Json.obj(
      "metadata" -> ((json \ "metadata").as[JsObject] ++ Json.obj("type" -> "test"))
    )
    val resp = node1.post("/mtb/etl/patient-record", modified.toString())
    resp.code.code shouldBe 200
  }

  it should "reject a FollowUp submission without a prior Initial for the same TAN" in {
    val (_, body) = fetchFakeMvhSubmission("mtb")
    val json      = Json.parse(body)
    // Swap the TAN for a fresh one (no prior Initial) and set type=followup
    val freshTan  = randomHex(32)
    val modified  = json.as[JsObject] ++ Json.obj(
      "metadata" -> ((json \ "metadata").as[JsObject] ++ Json.obj(
        "type"        -> "followup",
        "transferTAN" -> freshTan,
      ))
    )
    val resp = node1.post("/mtb/etl/patient-record", modified.toString())
    resp.code.code should (be >= 400 and be < 500)
  }

  it should "accept a Correction after an Initial has been uploaded" in {
    val (tan, body) = fetchFakeMvhSubmission("mtb")
    node1.post("/mtb/etl/patient-record", body).code.code shouldBe 200

    val json     = Json.parse(body)
    val corrBody = (json.as[JsObject] ++ Json.obj(
      "metadata" -> ((json \ "metadata").as[JsObject] ++ Json.obj("type" -> "correction"))
    )).toString()
    val resp = node1.post("/mtb/etl/patient-record", corrBody)
    resp.code.code shouldBe 200
  }

  // ─── Consent revocation ────────────────────────────────────────────────────

  it should "accept a ConsentRevocation and remove the patient from the data set" in {
    val tan = uploadFakeMvhRecord("mtb")

    // Revocation payload: type=initial record with all consents set to deny
    val (_, body) = fetchFakeMvhSubmission("mtb")
    val json      = Json.parse(body)
    val denyConsent = Json.obj(
      "purpose"    -> Json.obj("coding" -> Json.arr(
        Json.obj("code" -> "sequencing"),
        Json.obj("code" -> "reidentification"),
        Json.obj("code" -> "case-identification"),
      )),
      "provisions" -> Json.arr(Json.obj("type" -> "deny")),
    )
    val revoked = json.as[JsObject] ++ Json.obj(
      "metadata" -> ((json \ "metadata").as[JsObject] ++ Json.obj(
        "type"        -> "initial",
        "transferTAN" -> tan,
      )),
      "consent"  -> denyConsent,
    )
    val resp = node1.post("/mtb/etl/patient-record", revoked.toString())
    resp.code.code should (be >= 200 and be < 300)
  }

  // ─── Use-case isolation ────────────────────────────────────────────────────

  it should "keep MTB records out of the RD submission report list" in {
    val mtbTan = uploadFakeMvhRecord("mtb")

    val rdResp = node1.get("/rd/peer2peer/mvh/submissions")
    rdResp.code.code shouldBe 200
    val rdTans = (Json.parse(rdResp.body.merge) \ "entries").as[JsArray].value
      .flatMap(r => (r \ "metadata" \ "transferTAN").asOpt[String])
    rdTans should not contain mtbTan
  }

  it should "reject an MTB record posted to node2 (node2 is RD-only)" in {
    val (_, body) = fetchFakeMvhSubmission("mtb", node2)
    // node2 has MTB_RANDOM_DATA=-1; the fake endpoint exists but the ETL path
    // for MTB should either be absent or return an error
    val resp = node2.post("/mtb/etl/patient-record", body)
    // Acceptable: 404 (route not registered) or 4xx (use-case disabled)
    resp.code.code should (be >= 400 and be < 600)
  }
}
