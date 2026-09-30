package de.dnpm.dip.integration.support

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import play.api.libs.json.{JsObject, Json}

/** Queue, archive and log access to a running zKDK (central-data-node) container.
 *
 *  Paths are those set in the central-data-node Dockerfile (CCDN_QUEUE_DIR, CCDN_QUARTERBACKUP_DIR).
 *  Queue files are only read by the zKDK at startup, so files written here take effect after [[restart]].
 */
class CcdnContainer(val service: String) {

  val queueDir   = "/ccdn_data/queue"
  val archiveDir = "/ccdn_data/quarter_report"

  // Globbing with shell builtins only: the image does not guarantee `find`
  private def glob(pattern: String): Seq[String] =
    DockerCompose.exec(service, s"""for f in $pattern; do [ -e "$$f" ] && echo "$$f"; done; true""")
      .linesIterator.filter(_.nonEmpty).toSeq

  def queueFile(tan: String): Option[String] = glob(s"$queueDir/*$tan*.json").headOption

  def queuedReports: Seq[JsObject] = glob(s"$queueDir/*.json").map(p => Json.parse(read(p)).as[JsObject])

  /** Where ArchivingReportRepository moved the report after the last workflow step. */
  def archivedFile(tan: String): Option[String] = glob(s"$archiveDir/*/*$tan*.json").headOption

  def read(path: String): String = DockerCompose.exec(service, s"cat '$path'")

  def write(path: String, content: String): Unit = {
    DockerCompose.exec(service, s"cat > '$path'", Some(content.getBytes(UTF_8)))
    ()
  }

  def logsSince(since: Instant): String = DockerCompose.logsSince(service, since)

  /** Restart the container; returns a timestamp for [[logsSince]] that covers the new process. */
  def restart(): Instant = {
    val since = Instant.now().minusSeconds(1)
    DockerCompose.restartService(service)
    since
  }

  /** Recreate the container with `env` applied to docker-compose.yml; returns a timestamp for [[logsSince]]. */
  def recreate(env: (String, String)*): Instant = {
    val since = Instant.now().minusSeconds(1)
    DockerCompose.recreateService(service, env: _*)
    since
  }

  def workflowCyclesSince(since: Instant): Int =
    logsSince(since).linesIterator.count(_.contains("Conducting scheduled reporting workflow"))

  /** For each of `tans` whose submission was backed up since `since` (DEBUG "Backed up Submission …" of
   *  MongodbPersistenceServiceImpl), the number of the workflow cycle it happened in, counting from 1.
   *  Cycles do not overlap: the scheduler awaits each workflow run.
   */
  def submissionBackupCycles(since: Instant, tans: Seq[String]): Map[String, Int] =
    logsSince(since).linesIterator.foldLeft((0, Map.empty[String, Int])) {
      case ((cycle, found), line) if line.contains("Conducting scheduled reporting workflow") => (cycle + 1, found)
      case ((cycle, found), line) =>
        (cycle, found ++ tans.find(tan => line.contains(s"Backed up Submission $tan from site")).map(_ -> cycle))
    }._2

  /** Whether the logs contain the WARN that MongodbPersistenceServiceImpl emits when skipping an existing backup. */
  def warnedExistingBackup(since: Instant, kind: String, tan: String): Boolean =
    logsSince(since).linesIterator.exists(l =>
      l.contains("WARN") && l.contains(s"Backup of $kind $tan from site") && l.contains("already exists; skipped")
    )
}
