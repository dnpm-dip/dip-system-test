package de.dnpm.dip.integration.support

import java.nio.file.{Files, Path, Paths}
import java.util.Comparator
import scala.jdk.CollectionConverters._
import scala.sys.process._
import play.api.libs.json.{JsValue, Json}
import sttp.client3._

/** backup-extract.sh of central-data-node-deployment, i.e. the extraction of the zKDK backups used in PROD.
 *
 *  Downloaded anonymously (the repository is public) from its main branch and run unmodified: it addresses
 *  the "mongodb" service of docker-compose.yml in the folder it lies in, so it is placed into a temporary
 *  folder and pointed to this project's stack by COMPOSE_FILE and COMPOSE_PROJECT_NAME.
 *  Needs openssl, base64, od and numfmt on the host.
 */
object BackupExtractScript {

  val Url = "https://raw.githubusercontent.com/dnpm-dip/central-data-node-deployment/main/backup-extract.sh"

  private val projectDir  = Paths.get("").toAbsolutePath
  private val composeFile = projectDir.resolve("docker-compose.yml").toString

  private lazy val script: String = {
    val resp = basicRequest.get(uri"$Url").send(HttpClientSyncBackend())
    resp.body.fold(
      error => throw new IllegalStateException(s"Download of $Url failed: ${resp.code} ${error.take(500)}"),
      identity
    )
  }

  /** Name of this project's running stack, which the script must address instead of its temporary folder */
  private lazy val projectName: String =
    (Json.parse(Process(Seq("docker", "compose", "-f", composeFile, "config", "--format", "json")).!!) \ "name").as[String]

  /** Run the script for `tan`; returns the decrypted payloads of its backups, by backup type. */
  def run(tan: String): Map[String, JsValue] = {
    val dir = Files.createTempDirectory("backup-extract")
    try {
      val scriptFile = Files.writeString(dir.resolve("backup-extract.sh"), script)
      val output     = dir.resolve("output")
      val log        = new StringBuilder
      val code = Process(
        Seq(
          "bash", scriptFile.toString,
          "--tan", tan,
          "--output", output.toString,
          "--key", projectDir.resolve("crypto/private.pem").toString,
          "--min-free", "0",
          "--yes"
        ),
        dir.toFile,
        "COMPOSE_FILE"               -> composeFile,
        "COMPOSE_PROJECT_NAME"       -> projectName,
        // Like openssl's -passin file:, only the first line is the passphrase
        "CCDN_BACKUP_KEY_PASSPHRASE" -> Files.readString(projectDir.resolve("crypto/private_passphrase.txt")).linesIterator.next()
      ) ! ProcessLogger(l => log.append(l).append('\n'))
      require(code == 0, s"backup-extract.sh failed (exit $code):\n$log")

      // Output layout: <output>/<site>/<usecase>/<tan>.<type>.json
      val prefix = s"$tan."
      Files.walk(output).iterator.asScala.toSeq
        .filter(p => Files.isRegularFile(p))
        .map(p => p -> p.getFileName.toString)
        .collect { case (p, n) if n.startsWith(prefix) && n.endsWith(".json") =>
          n.stripPrefix(prefix).stripSuffix(".json") -> Json.parse(Files.readString(p))
        }
        .toMap
    } finally
      Files.walk(dir).sorted(Comparator.reverseOrder[Path]()).forEach(p => Files.delete(p))
  }
}
