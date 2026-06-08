package de.dnpm.dip.integration

import sttp.client3._

/** HTTP client for a single DIP node (api-gateway).
 *
 *  All methods return the raw sttp [[Response]] so callers can inspect both
 *  the status code and the body.  The body is always read as a String.
 */
class DipNodeClient(val baseUrl: String) {

  private val backend = HttpClientSyncBackend()

  def get(path: String, token: Option[String] = None): Response[Either[String, String]] = {
    val req = basicRequest.get(uri"$baseUrl$path")
    token.fold(req)(t => req.header("Authorization", s"Bearer $t")).send(backend)
  }

  def post(path: String, body: String, token: Option[String] = None): Response[Either[String, String]] = {
    val req = basicRequest
      .post(uri"$baseUrl$path")
      .contentType("application/json")
      .body(body)
    token.fold(req)(t => req.header("Authorization", s"Bearer $t")).send(backend)
  }

  def postForm(path: String, fields: Map[String, String]): Response[Either[String, String]] = {
    basicRequest
      .post(uri"$baseUrl$path")
      .contentType("application/x-www-form-urlencoded")
      .body(fields.map { case (k, v) => s"$k=${java.net.URLEncoder.encode(v, "UTF-8")}" }.mkString("&"))
      .send(backend)
  }

  def delete(path: String, token: Option[String] = None): Response[Either[String, String]] = {
    val req = basicRequest.delete(uri"$baseUrl$path")
    token.fold(req)(t => req.header("Authorization", s"Bearer $t")).send(backend)
  }

  /** Fetch a bearer token from an Authup server (client-credentials grant). */
  def authToken(authupUrl: String, clientSecret: String = "start123"): String = {
    import play.api.libs.json._
    val resp = basicRequest
      .post(uri"$authupUrl/token")
      .contentType("application/json")
      .body(s"""{"grant_type":"client_credentials","client_id":"system","client_secret":"$clientSecret"}""")
      .send(backend)
    require(resp.code.isSuccess, s"Authup token request failed: ${resp.code} ${resp.body.merge}")
    (Json.parse(resp.body.merge) \ "access_token").as[String]
  }
}
