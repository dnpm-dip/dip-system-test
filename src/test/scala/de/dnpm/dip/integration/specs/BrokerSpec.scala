package de.dnpm.dip.integration.specs

import de.dnpm.dip.integration.support.{DipIntegrationSuite, DipNodeClient}
import play.api.libs.json._

/** Tests for the local nginx broker used in the test environment. */
class BrokerSpec extends DipIntegrationSuite {

  "Broker GET /sites" should "return a JSON array with both registered nodes" in {
    val resp = broker.get("/sites")
    withClue(s"Response body: ${resp.body.merge}\n") {
      resp.code.code shouldBe 200
    }

    val json  = Json.parse(resp.body.getOrElse(fail("Unexpected error body")))
    val sites = (json \ "sites").as[JsArray].value

    sites.size should be >= 2

    val ids = sites.flatMap(s => (s \ "id").asOpt[String])
    ids should contain("UKT")
    ids should contain("UKL")
  }

  it should "include a virtualhost entry per site" in {
    val resp  = broker.get("/sites")
    val body  = resp.body.getOrElse(fail("Unexpected error body"))
    val sites = (Json.parse(body) \ "sites").as[JsArray].value
    sites.foreach { site =>
      (site \ "virtualhost").asOpt[String] shouldBe defined
    }
  }

  // ─── Per-node smoke tests ─────────────────────────────────────────────────

  private val nodeFixtures: List[(String, DipNodeClient, String)] = List(
    ("DIP node1", node1, "mtb"),
    ("DIP node2", node2, "rd"),
  )

  for ((nodeName, client, useCase) <- nodeFixtures) {

    nodeName should "respond on the health / fake-data endpoint" in {
      val resp = client.get(s"/$useCase/fake/data/patient-record")
      resp.code.code shouldBe 200
      val body = Json.parse(resp.body.getOrElse(fail("Unexpected error body")))
      (body \ "patient").isDefined shouldBe true
    }

    nodeName should "expose the peer2peer status / version endpoint" in {
      val resp = client.get(s"/$useCase/peer2peer/mvh/submissions")
      resp.code.code shouldBe 200
    }
  }
}