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
}
