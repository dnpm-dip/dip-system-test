package de.dnpm.dip.integration.support

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
}