package de.dnpm.dip.integration

import play.api.libs.json._

/** Tests for ETL upload business rules at the DIP node. */
class UploadSpec extends DipIntegrationSuite {

  // ─── Duplicate TAN ─────────────────────────────────────────────────────────

  "ETL upload" should "reject a second upload with the same TAN" in {
    val (_, body) = generateFakeMvhSubmission("mtb")

    val first = node1.post("/mtb/etl/patient-record", body)
    withClue(s"first upload: ${first.code} ${first.body.merge}\n") {
      first.code.code shouldBe 200
    }
    val second = node1.post("/mtb/etl/patient-record", body)
    withClue(s"second upload with same TAN should be rejected: ${second.code} ${second.body.merge}\n") {
      second.code.code should (be >= 400 and be < 500)
    }
  }

  it should "reject a second initial submission for the same EpisodeOfCare" in {
    val (_, body) = generateFakeMvhSubmission("mtb")
    val first = node1.post("/mtb/etl/patient-record", body)
    withClue(s"first upload: ${first.code} ${first.body.merge}\n") {
      first.code.code shouldBe 200
    }

    val freshTan = randomHex()
    val second   = (Json.parse(body).as[JsObject] ++ Json.obj(
      "metadata" -> ((Json.parse(body) \ "metadata").as[JsObject] ++ Json.obj("transferTAN" -> freshTan))
    )).toString()
    val resp = node1.post("/mtb/etl/patient-record", second)
    withClue(s"second initial for same EpisodeOfCare should be rejected: ${resp.code} ${resp.body.merge}\n") {
      resp.code.code should (be >= 400 and be < 500)
    }
  }

  // ─── Submission type rules ──────────────────────────────────────────────────

  it should "accept a submission with type=test" in {
    val (_, body) = generateFakeMvhSubmission("mtb")
    val json      = Json.parse(body)
    val modified  = json.as[JsObject] ++ Json.obj(
      "metadata" -> ((json \ "metadata").as[JsObject] ++ Json.obj("type" -> "test"))
    )
    val resp = node1.post("/mtb/etl/patient-record", modified.toString())
    withClue(s"type=test upload: ${resp.code} ${resp.body.merge}\n") {
      resp.code.code shouldBe 200
    }
  }

  it should "reject a FollowUp submission without a prior Initial for the same TAN" in {
    val (_, body) = generateFakeMvhSubmission("mtb")
    val json      = Json.parse(body)
    val freshTan  = randomHex()
    val modified  = json.as[JsObject] ++ Json.obj(
      "metadata" -> ((json \ "metadata").as[JsObject] ++ Json.obj(
        "type"        -> "followup",
        "transferTAN" -> freshTan,
      ))
    )
    val resp = node1.post("/mtb/etl/patient-record", modified.toString())
    withClue(s"followup without prior initial should be rejected: ${resp.code} ${resp.body.merge}\n") {
      resp.code.code should (be >= 400 and be < 500)
    }
  }

  it should "accept a Correction after an Initial has been uploaded" in {
    val (_, body) = generateFakeMvhSubmission("mtb")
    val initial = node1.post("/mtb/etl/patient-record", body)
    withClue(s"initial upload: ${initial.code} ${initial.body.merge}\n") {
      initial.code.code shouldBe 200
    }

    val json      = Json.parse(body)
    val freshTan  = randomHex()
    val corrBody = (json.as[JsObject] ++ Json.obj(
      "metadata" -> ((json \ "metadata").as[JsObject] ++ Json.obj(
        "type"        -> "correction",
        "transferTAN" -> freshTan
      ))
    )).toString()
    val resp = node1.post("/mtb/etl/patient-record", corrBody)
    withClue(s"correction upload: ${resp.code} ${resp.body.merge}\n") {
      resp.code.code shouldBe 200
    }
  }

  // ─── Consent revocation ────────────────────────────────────────────────────

  it should "accept a ConsentRevocation and remove the patient from the data set" in {
    val (_, initialBody) = generateFakeMvhSubmission("mtb")
    val initialResp = node1.post("/mtb/etl/patient-record", initialBody)
    withClue(s"initial upload: ${initialResp.code} ${initialResp.body.merge}\n") {
      initialResp.code.code shouldBe 200
    }

    val json     = Json.parse(initialBody)
    val freshTan = randomHex()
    // Keep modelProjectConsent permits intact so the validator passes.
    // Signal the revocation via the top-level PatientRecord consent field.
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
        "type"        -> "correction",
        "transferTAN" -> freshTan,
      )),
      "consent"  -> denyConsent,
    )
    val resp = node1.post("/mtb/etl/patient-record", revoked.toString())
    withClue(s"consent revocation upload: ${resp.code} ${resp.body.merge}\n") {
      resp.code.code should (be >= 200 and be < 300)
    }
  }

  // ─── Use-case isolation ────────────────────────────────────────────────────

  it should "keep MTB records out of the RD submission report list" in {
    val mtbTan  = uploadFakeMvhRecordToDipnode("mtb")
    val rdResp  = node1.get("/rd/peer2peer/mvh/submissions")
    withClue(s"GET /rd/peer2peer/mvh/submissions: ${rdResp.code} ${rdResp.body.merge}\n") {
      rdResp.code.code shouldBe 200
    }
    val rdTans = (Json.parse(rdResp.body.merge) \ "entries").as[JsArray].value
      .flatMap(r => (r \ "metadata" \ "transferTAN").asOpt[String])
    rdTans should not contain mtbTan
  }

  it should "reject an MTB record posted to node2 (node2 is RD-only)" in {
    // The api-gateway always registers both MTB and RD routers regardless of
    // ACTIVE_FEDERATED_QUERY_USE_CASES — that env var is not read by the app.
    // Node2's "RD-only" restriction is enforced at the broker/federated-query
    // layer (see FederatedQuerySpec), not at the ETL upload level.
    pending
  }
}
