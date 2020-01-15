package esw.agent.app.utils

import csw.logging.api.scaladsl.Logger
import csw.prefix.models.Prefix
import esw.agent.app.AgentSettings

import scala.util.Try
import scala.util.control.NonFatal

// $COVERAGE-OFF$
class ProcessExecutor(output: ProcessOutput, agentSettings: AgentSettings, logger: Logger) {
  import logger._

  def runCommand(command: List[String], prefix: Prefix): Either[String, ProcessHandle] =
    Try {
      val processBuilder = new ProcessBuilder(command: _*)
      debug(s"starting command", Map("command" -> processBuilder.command()))
      val process = processBuilder.start()
      output.attachToProcess(process, prefix.toString.toLowerCase)
      debug(s"new process spawned", Map("pid" -> process.pid()))
      process.toHandle
    }.toEither.left.map {
      case NonFatal(err) =>
        error("command failed to run", map = Map("command" -> command, "prefix" -> prefix.value), ex = err)
        err.getMessage
        error("command failed to run", map = Map("command" -> command, "prefix" -> prefix.toString.toLowerCase), ex = err)
    }
}
// $COVERAGE-ON$
