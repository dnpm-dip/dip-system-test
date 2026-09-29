package de.dnpm.dip.integration.specs

import java.time.LocalDateTime
import java.util.UUID
import play.api.libs.json._
import de.dnpm.dip.integration.support.DipIntegrationSuite

/** Tests for the api-gateway MVH peer2peer and controlling endpoints (api-gateway ≥ 1.3). */
class MvhApiSpec extends DipIntegrationSuite {

  // ─── Submissions by TAN ────────────────────────────────────────────────────

  "The MVH peer2peer API" should "serve the full submission for a TAN" in {
    val patientId = UUID.randomUUID().toString
    val tan       = uploadFakeMvhRecordToDipnode("mtb", patientId = patientId)

    val resp = node1.get(s"/mtb/peer2peer/mvh/submissions/$tan")
    withClue(s"GET /mtb/peer2peer/mvh/submissions/$tan: ${resp.code} ${resp.body.merge}\n") {
      resp.code.code shouldBe 200
    }
    val json = Json.parse(resp.body.getOrElse(fail("Unexpected error body")))
    (json \ "metadata" \ "transferTAN").asOpt[String] shouldBe Some(tan)
    (json \ "submittedAt").asOpt[String] shouldBe defined
    Json.stringify(json) should include(patientId)
  }

  it should "return 404 for a submission with an unknown TAN" in {
    val resp = node1.get(s"/mtb/peer2peer/mvh/submissions/${randomHex()}")
    withClue(s"GET submission with unknown TAN: ${resp.code} ${resp.body.merge}\n") {
      resp.code.code shouldBe 404
    }
  }

  // ─── DeletionEvents ────────────────────────────────────────────────────────

  it should "only list DeletionEvents after the timestamp given in 'after'" in {
    val patientId = UUID.randomUUID().toString
    val tan       = uploadFakeMvhRecordToDipnode("mtb", patientId = patientId)
    awaitFlushedByZkdk(tan, "mtb")

    val deleteResp = node1.delete(s"/mtb/etl/patient/$patientId")
    withClue(s"DELETE patient: ${deleteResp.code} ${deleteResp.body.merge}\n") {
      deleteResp.code.code shouldBe 200
    }
    val event = deletionEvents(node1, "mtb")
      .find(e => (e \ "tan").asOpt[String].contains(tan))
      .getOrElse(fail(s"No DeletionEvent for TAN=$tan"))
    val deletedAt = LocalDateTime.parse((event \ "dateTime").as[String])

    def tansAfter(t: LocalDateTime) = deletionEvents(node1, "mtb", after = Some(t)).flatMap(e => (e \ "tan").asOpt[String])

    tansAfter(deletedAt.minusSeconds(1)) should contain(tan)
    tansAfter(deletedAt.plusSeconds(1)) should not contain tan
  }

  // ─── Controlling ───────────────────────────────────────────────────────────

  for (useCase <- Seq("mtb", "rd"); endpoint <- Seq("local-controlling-info", "federated-controlling-info"))
    "The controlling API" should s"serve $useCase $endpoint" in {
      val resp = node1.get(s"/$useCase/controlling/$endpoint")
      withClue(s"GET /$useCase/controlling/$endpoint: ${resp.code} ${resp.body.merge}\n") {
        resp.code.code shouldBe 200
      }
      noException should be thrownBy Json.parse(resp.body.getOrElse(fail("Unexpected error body")))
    }
}
