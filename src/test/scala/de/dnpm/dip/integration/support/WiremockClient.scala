package de.dnpm.dip.integration

import play.api.libs.json._
import sttp.client3._

/** Thin wrapper around the Wiremock admin API. */
class WiremockClient(adminBase: String) {

  private val backend = HttpClientSyncBackend()

  /** Number of requests Wiremock received whose URL matches `urlPattern` (regex). */
  def requestCount(urlPattern: String): Int = {
    val body = Json.obj("urlPattern" -> urlPattern).toString()
    val resp = basicRequest
      .post(uri"$adminBase/__admin/requests/count")
      .contentType("application/json")
      .body(body)
      .send(backend)
    require(resp.code.isSuccess, s"Wiremock count failed: ${resp.code}")
    (Json.parse(resp.body.merge) \ "count").as[Int]
  }

  /** Reset all recorded requests (call in beforeEach / between tests that inspect counts). */
  def resetRequests(): Unit = {
    basicRequest.delete(uri"$adminBase/__admin/requests").send(backend)
    ()
  }

  /** All request log entries as raw JSON. */
  def allRequests(): JsArray = {
    val resp = basicRequest.get(uri"$adminBase/__admin/requests").send(backend)
    require(resp.code.isSuccess, s"Wiremock requests failed: ${resp.code}")
    (Json.parse(resp.body.merge) \ "requests").as[JsArray]
  }

  /** Register a stub mapping; returns the generated stub ID for later removal. */
  def addStub(mapping: JsObject): String = {
    val resp = basicRequest
      .post(uri"$adminBase/__admin/mappings")
      .contentType("application/json")
      .body(mapping.toString())
      .send(backend)
    require(resp.code.isSuccess, s"Wiremock addStub failed: ${resp.code}")
    (Json.parse(resp.body.merge) \ "id").as[String]
  }

  /** Remove a previously registered stub mapping by ID. */
  def removeStub(stubId: String): Unit = {
    basicRequest.delete(uri"$adminBase/__admin/mappings/$stubId").send(backend)
    ()
  }
}
