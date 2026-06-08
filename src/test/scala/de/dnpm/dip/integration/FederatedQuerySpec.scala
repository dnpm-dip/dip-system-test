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

  private lazy val token1 = node1.authToken(DipTestEnvironment.authup1Url)

  // ─── MTB queries ───────────────────────────────────────────────────────────

  "Federated MTB query" should "return a query ID with status 201" in {
    val resp = node1.post("/api/mtb/queries", "{}", Some(token1))
    withClue(s"Response body: ${resp.body.merge}\n") {
      resp.code.code shouldBe 201
    }
    val id = (Json.parse(resp.body.merge) \ "id").asOpt[String]
    id shouldBe defined
  }

  it should "return patient summaries from at least node1" in {
    val createResp = node1.post("/api/mtb/queries", "{}", Some(token1))
    createResp.code.code shouldBe 201
    val queryId = (Json.parse(createResp.body.merge) \ "id").as[String]

    val summariesResp = node1.get(s"/api/mtb/queries/$queryId/patient-summaries", Some(token1))
    summariesResp.code.code shouldBe 200

    val total = (Json.parse(summariesResp.body.merge) \ "total").asOpt[Int]
      .orElse((Json.parse(summariesResp.body.merge) \ "count").asOpt[Int])
      .getOrElse {
        // Fall back to counting array entries
        Json.parse(summariesResp.body.merge) match {
          case arr: JsArray => arr.value.size
          case obj: JsObject =>
            (obj \ "entries").asOpt[JsArray].map(_.value.size).getOrElse(0)
          case _ => 0
        }
      }

    // node1 has 50 random MTB records loaded at startup
    total should be > 0
  }

  it should "not include node2 results in an MTB query (node2 is RD-only)" in {
    val createResp = node1.post("/api/mtb/queries", "{}", Some(token1))
    createResp.code.code shouldBe 201
    val queryId = (Json.parse(createResp.body.merge) \ "id").as[String]

    val resp = node1.get(s"/api/mtb/queries/$queryId", Some(token1))
    resp.code.code shouldBe 200

    val json = Json.parse(resp.body.merge)
    // The query result should reference only UKT (node1); UKL does not participate in MTB
    val siteIds = (json \ "sites").asOpt[JsArray]
      .map(_.value.flatMap(s => (s \ "id").asOpt[String]))
      .getOrElse(Seq.empty)

    siteIds should not contain "UKL"
  }

  // ─── RD queries ────────────────────────────────────────────────────────────

  "Federated RD query" should "return results from both nodes" in {
    val createResp = node1.post("/api/rd/queries", "{}", Some(token1))
    createResp.code.code shouldBe 201
    val queryId = (Json.parse(createResp.body.merge) \ "id").as[String]

    val resp = node1.get(s"/api/rd/queries/$queryId", Some(token1))
    resp.code.code shouldBe 200

    val json    = Json.parse(resp.body.merge)
    val siteIds = (json \ "sites").asOpt[JsArray]
      .map(_.value.flatMap(s => (s \ "id").asOpt[String]))
      .getOrElse(Seq.empty)

    // Both nodes export RD
    siteIds should contain("UKT")
    siteIds should contain("UKL")
  }

  // ─── Edge cases ─────────────────────────────────────────────────────────────

  "Federated query" should "require authentication" in {
    val resp = node1.post("/api/mtb/queries", "{}", token = None)
    resp.code.code should (be >= 400 and be < 500)
  }

  it should "return 404 for an unknown query ID" in {
    val resp = node1.get("/api/mtb/queries/no-such-id-xyz", Some(token1))
    resp.code.code shouldBe 404
  }
}
