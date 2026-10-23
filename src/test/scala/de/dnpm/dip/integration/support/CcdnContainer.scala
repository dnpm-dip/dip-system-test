package de.dnpm.dip.integration.support

import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Instant, LocalTime}
import play.api.libs.json.{JsObject, Json}

/** Queue, archive and log access to a running zKDK (central-data-node) container.
 *
 *  Paths are those set in the central-data-node Dockerfile (CCDN_QUEUE_DIR, CCDN_QUARTERBACKUP_DIR).
 *  Since central-archive:1.2 the zKDK deletes dequeued reports instead of archiving them (ArchivingReportRepository
 *  was removed in favour of the quarter-reports collection), so the archive should stay empty for new TANs.
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

  /** Where the removed ArchivingReportRepository moved the report after the last workflow step. */
  def archivedFile(tan: String): Option[String] = glob(s"$archiveDir/*/*$tan*.json").headOption

  /** Write `report` into the queue under the file name FSBackedReportRepository uses. */
  def enqueue(report: JsObject): Unit =
    write(queueFileName(report), Json.stringify(report))

  private def queueFileName(report: JsObject): String =
    s"$queueDir/Report_${(report \ "site" \ "code").as[String]}_${(report \ "id").as[String]}.json"

  /** Like [[enqueue]], but while the container is stopped (see [[stop]]). */
  def enqueueWhileStopped(report: JsObject): Unit = {
    DockerCompose.run(service, s"cat > '${queueFileName(report)}'", Some(Json.stringify(report).getBytes(UTF_8)))
    ()
  }

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

  def stop(): Unit = DockerCompose.stopService(service)

  /** Start the stopped container; returns a timestamp for [[logsSince]] that covers the new process. */
  def start(): Instant = {
    val since = Instant.now().minusSeconds(1)
    DockerCompose.startService(service)
    since
  }

  /** How often Docker has restarted the current container, i.e. how often the zKDK process exited (restart: unless-stopped). */
  def restartCount: Int = DockerCompose.restartCount(service)

  /** Recreate the container with `env` applied to docker-compose.yml; returns a timestamp for [[logsSince]]. */
  def recreate(env: (String, String)*): Instant = {
    val since = Instant.now().minusSeconds(1)
    DockerCompose.recreateService(service, env: _*)
    since
  }

  def workflowCyclesSince(since: Instant): Int =
    logsSince(since).linesIterator.count(_.contains("Conducting scheduled reporting workflow"))

  /** Runs of the deletion workflow, which applies DeletionEvents separately from the reporting workflow. */
  def deletionCyclesSince(since: Instant): Int =
    logsSince(since).linesIterator.count(_.contains("Conducting scheduled deletion workflow"))

  /** Whether the logs contain the WARN that MongodbLongTermStore emits when skipping an existing backup. */
  def warnedExistingBackup(since: Instant, kind: String, tan: String): Boolean =
    logsSince(since).linesIterator.exists(l =>
      l.contains("WARN") && l.contains(s"Backup of $kind $tan from site") && l.contains("already exists; skipped")
    )
}

object CcdnContainer {

  /** A start ("Conducting scheduled … workflow") or end ("… workflow completed") line of one of the two workflows
   *  MVHReportingService schedules; `kind` is "reporting" or "deletion", `index` the line's position in the logs.
   *  Only start lines are logged on the scheduler thread; end lines come from whichever thread of the global
   *  execution context completes the workflow's Future.
   */
  final case class WorkflowEvent(index: Int, time: LocalTime, thread: String, kind: String, started: Boolean)

  // Console pattern of config/ccdn-*/logback.xml: "%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n"
  private val LogLine  = """^(\d{2}:\d{2}:\d{2}\.\d{3}) \[([^\]]+)\] .*""".r
  private val Started  = """.* - Conducting scheduled (reporting|deletion) workflow.*""".r
  private val Finished = """.* - (Reporting|Deletion) workflow completed.*""".r

  def workflowEvents(lines: IndexedSeq[String]): Seq[WorkflowEvent] =
    lines.zipWithIndex.collect {
      case (line @ LogLine(time, thread), i) if Started.matches(line) || Finished.matches(line) =>
        line match {
          case Started(kind)  => WorkflowEvent(i, LocalTime.parse(time), thread, kind, started = true)
          case Finished(kind) => WorkflowEvent(i, LocalTime.parse(time), thread, kind.toLowerCase, started = false)
        }
    }
}
