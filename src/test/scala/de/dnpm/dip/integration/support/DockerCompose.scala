package de.dnpm.dip.integration.support

import java.io.ByteArrayInputStream
import java.time.Instant
import scala.sys.process._

object DockerCompose {

  def up(): Unit = {
    val code = Seq("docker", "compose", "up", "-d").!
    require(code == 0, s"docker compose up failed (exit $code)")
  }

  def down(): Unit =
    Seq("docker", "compose", "down").!

  def pauseService(service: String): Unit =
    Seq("docker", "compose", "pause", service).!

  def unpauseService(service: String): Unit =
    Seq("docker", "compose", "unpause", service).!

  def restartService(service: String): Unit = {
    val code = Seq("docker", "compose", "restart", service).!
    require(code == 0, s"docker compose restart $service failed (exit $code)")
  }

  def stopService(service: String): Unit = {
    val code = Seq("docker", "compose", "stop", service).!
    require(code == 0, s"docker compose stop $service failed (exit $code)")
  }

  def startService(service: String): Unit = {
    val code = Seq("docker", "compose", "start", service).!
    require(code == 0, s"docker compose start $service failed (exit $code)")
  }

  /** How often Docker has restarted the service's current container (restart policy), e.g. after its process exited. */
  def restartCount(service: String): Int = {
    val id = Seq("docker", "compose", "ps", "--all", "--quiet", service).!!.trim
    require(id.nonEmpty, s"no container for service $service")
    Seq("docker", "inspect", "--format", "{{.RestartCount}}", id).!!.trim.toInt
  }

  /** Recreate a single service; `env` is visible to the variable interpolation in docker-compose.yml. */
  def recreateService(service: String, env: (String, String)*): Unit = {
    val code = Process(Seq("docker", "compose", "up", "-d", "--no-deps", "--force-recreate", service), None, env: _*).!
    require(code == 0, s"docker compose up --force-recreate $service failed (exit $code)")
  }

  def logsSince(service: String, since: Instant): String =
    Seq("docker", "compose", "logs", "--no-log-prefix", "--since", since.toString, service).!!

  /** Run `script` with `sh -c` inside the service's container and return its stdout. */
  def exec(service: String, script: String, stdin: Option[Array[Byte]] = None): String = {
    val cmd = Process(Seq("docker", "compose", "exec", "-T", service, "sh", "-c", script))
    stdin.fold(cmd.!!)(bytes => (cmd #< new ByteArrayInputStream(bytes)).!!)
  }

  /** Like [[exec]], but in a one-off container of the service (with its volumes), so it works while the service is stopped. */
  def run(service: String, script: String, stdin: Option[Array[Byte]] = None): String = {
    val cmd = Process(Seq("docker", "compose", "run", "--rm", "--no-deps", "-T", "--entrypoint", "sh", service, "-c", script))
    stdin.fold(cmd.!!)(bytes => (cmd #< new ByteArrayInputStream(bytes)).!!)
  }
}
