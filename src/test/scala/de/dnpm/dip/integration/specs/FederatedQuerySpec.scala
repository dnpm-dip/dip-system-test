package de.dnpm.dip.integration

import play.api.libs.json._

/** Tests for the federated query flow.
 *
 *  node1 (UKT): MTB + RD data, ACTIVE_FEDERATED_QUERY_USE_CASES=MTB,RD
 *  node2 (UKL): RD only,       ACTIVE_FEDERATED_QUERY_USE_CASES=RD
 *
 *  Query submission requires an auth token (Authup client-credentials grant).
 *
 *  Note: the exact shape of the query request body may need adjustment
 *  depending on the mtb-query-service version deployed.  An empty JSON
 *  object `{}` is used here as the most permissive query criterion.
 */
class FederatedQuerySpec extends DipIntegrationSuite {

  private lazy val token1 = node1.authToken(DipTestEnvironment.dipAuthup1Url)

  // ─── MTB queries ───────────────────────────────────────────────────────────

  private val queryBody = """{"mode":{"code":"federated"}}"""

  "Federated MTB query" should "return a query ID with status 200" in {
    val resp = node1.post("/mtb/queries", queryBody, Some(token1))
    withClue(s"Response body: ${resp.body.merge}\n") {
      resp.code.code shouldBe 200
    }
    val id = (Json.parse(resp.body.merge) \ "id").asOpt[String]
    id shouldBe defined
  }

  it should "return patient matches from at least node1" in {
    val createResp = node1.post("/mtb/queries", queryBody, Some(token1))
    createResp.code.code shouldBe 200
    val queryId = (Json.parse(createResp.body.merge) \ "id").as[String]

    val matchesResp = node1.get(s"/mtb/queries/$queryId/patient-matches", Some(token1))
    matchesResp.code.code shouldBe 200

    val total = (Json.parse(matchesResp.body.merge) \ "size").asOpt[Int]
      .orElse((Json.parse(matchesResp.body.merge) \ "total").asOpt[Int])
      .getOrElse {
        Json.parse(matchesResp.body.merge) match {
          case obj: JsObject =>
            (obj \ "entries").asOpt[JsArray].map(_.value.size).getOrElse(0)
          case arr: JsArray => arr.value.size
          case _ => 0
        }
      }

    // node1 has 50 random MTB records loaded at startup
    total should be > 0
  }

  it should "not include node2 results in an MTB query (node2 is RD-only)" in {
    val createResp = node1.post("/mtb/queries", queryBody, Some(token1))
    createResp.code.code shouldBe 200
    val queryId = (Json.parse(createResp.body.merge) \ "id").as[String]

    val resp = node1.get(s"/mtb/queries/$queryId", Some(token1))
    resp.code.code shouldBe 200

    val json = Json.parse(resp.body.merge)
    // peers shows which sites were contacted; UKL is RD-only so should not be online for MTB
    val onlineSites = (json \ "peers").asOpt[JsArray]
      .map(_.value.filter(p => (p \ "status").asOpt[String].contains("online"))
                  .flatMap(p => (p \ "site" \ "code").asOpt[String]))
      .getOrElse(Seq.empty)

    onlineSites should not contain "UKL"
  }

  // ─── RD queries ────────────────────────────────────────────────────────────

  "Federated RD query" should "return results from both nodes" in {
    val createResp = node1.post("/rd/queries", queryBody, Some(token1))
    createResp.code.code shouldBe 200
    val queryId = (Json.parse(createResp.body.merge) \ "id").as[String]

    val resp = node1.get(s"/rd/queries/$queryId", Some(token1))
    resp.code.code shouldBe 200

    val json     = Json.parse(resp.body.merge)
    val siteIds  = (json \ "peers").asOpt[JsArray]
      .map(_.value.flatMap(p => (p \ "site" \ "code").asOpt[String]))
      .getOrElse(Seq.empty)

    // Both nodes export RD
    siteIds should contain("UKT")
    siteIds should contain("UKL")
  }

  // ─── Edge cases ─────────────────────────────────────────────────────────────

  "Federated query" should "require authentication" in {
    val resp = node1.post("/mtb/queries", queryBody, token = None)
    resp.code.code should (be >= 400 and be < 500)
  }

  it should "return 404 for an unknown query ID" in {
    val resp = node1.get("/mtb/queries/no-such-id-xyz", Some(token1))
    resp.code.code shouldBe 404
  }
}
