package de.dnpm.dip.integration.support

import sttp.client3._
import sttp.model.Uri

/** HTTP client for a single DIP node (api-gateway).
 *
 *  All methods return the raw sttp [[Response]] so callers can inspect both
 *  the status code and the body.  The body is always read as a String.
 */
class DipNodeClient(val baseUrl: String) {

  private val backend = HttpClientSyncBackend()

  private def url(path: String): Uri = Uri.unsafeParse(s"$baseUrl$path")

  def get(path: String, token: Option[String] = None): Response[Either[String, String]] = {
    val req = basicRequest.get(url(path))
    token.fold(req)(t => req.header("Authorization", s"Bearer $t")).send(backend)
  }

  def post(path: String, body: String, token: Option[String] = None): Response[Either[String, String]] = {
    val req = basicRequest
      .post(url(path))
      .contentType("application/json")
      .body(body)
    token.fold(req)(t => req.header("Authorization", s"Bearer $t")).send(backend)
  }

  def postForm(path: String, fields: Map[String, String]): Response[Either[String, String]] = {
    basicRequest
      .post(url(path))
      .contentType("application/x-www-form-urlencoded")
      .body(fields.map { case (k, v) => s"$k=${java.net.URLEncoder.encode(v, "UTF-8")}" }.mkString("&"))
      .send(backend)
  }

  def delete(path: String, token: Option[String] = None): Response[Either[String, String]] = {
    val req = basicRequest.delete(url(path))
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
    (Json.parse(resp.body.getOrElse(throw new RuntimeException("Unexpected error body"))) \ "access_token").as[String]
  }
}
