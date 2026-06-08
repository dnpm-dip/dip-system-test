package de.dnpm.dip.integration

import play.api.libs.json._

/** Tests for the local nginx broker used in the test environment. */
class BrokerSpec extends DipIntegrationSuite {

  "Broker GET /sites" should "return a JSON array with both registered nodes" in {
    val resp = broker.get("/sites")
    withClue(s"Response body: ${resp.body.merge}\n") {
      resp.code.code shouldBe 200
    }

    val json  = Json.parse(resp.body.merge)
    val sites = (json \ "sites").as[JsArray].value

    sites.size should be >= 2

    val ids = sites.flatMap(s => (s \ "id").asOpt[String])
    ids should contain("UKT")
    ids should contain("UKL")
  }

  it should "include a virtualhost entry per site" in {
    val resp  = broker.get("/sites")
    val sites = (Json.parse(resp.body.merge) \ "sites").as[JsArray].value
    sites.foreach { site =>
      (site \ "virtualhost").asOpt[String] shouldBe defined
    }
  }

  "DIP node1" should "respond on the health / fake-data endpoint" in {
    val resp = node1.get("/api/mtb/fake/data/patient-record")
    resp.code.code shouldBe 200
    val body = Json.parse(resp.body.merge)
    (body \ "patient").isDefined shouldBe true
  }

  "DIP node2" should "respond on the health / fake-data endpoint" in {
    val resp = node2.get("/api/rd/fake/data/patient-record")
    resp.code.code shouldBe 200
    val body = Json.parse(resp.body.merge)
    (body \ "patient").isDefined shouldBe true
  }

  "DIP node1" should "expose the peer2peer status / version endpoint" in {
    // Any 2xx from the peer2peer namespace confirms the service is reachable
    val resp = node1.get("/api/mtb/peer2peer/mvh/submission/report")
    resp.code.code shouldBe 200
  }

  "DIP node2" should "expose the peer2peer status / version endpoint" in {
    val resp = node2.get("/api/rd/peer2peer/mvh/submission/report")
    resp.code.code shouldBe 200
  }
}
