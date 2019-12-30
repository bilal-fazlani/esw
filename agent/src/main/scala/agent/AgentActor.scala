package agent

import agent.AgentActor.AgentState
import agent.AgentCommand.{KillAllProcesses, ProcessRegistered, ProcessRegistrationFailed, SpawnCommand}
import agent.AgentCommand.SpawnCommand.SpawnSequenceComponent
import agent.Response.{Failed, Spawned}
import agent.utils.ProcessOutput
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import csw.location.api.scaladsl.LocationService
import csw.location.models.Connection.AkkaConnection
import csw.location.models.{ComponentId, ComponentType}
import csw.logging.api.scaladsl.Logger

import scala.compat.java8.OptionConverters.RichOptionalGeneric
import scala.concurrent.duration.DurationInt
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

//todo: test - spawned processes should run in background even if agent process dies
class AgentActor(locationService: LocationService, processOutput: ProcessOutput) {

  private val log: Logger = AgentLogger.getLogger
  import log._

  def behavior(state: AgentState): Behavior[AgentCommand] = Behaviors.receive { (ctx, command) =>
    command match {
      case command @ SpawnSequenceComponent(replyTo, prefix) =>
        debug(s"spawning sequence component", map = Map("prefix" -> prefix))
        runCommand(command, processOutput) match {
          case Left(err) =>
            replyTo ! err
          case Right(pid) =>
            val akkaLocF =
              locationService.resolve(AkkaConnection(ComponentId(prefix, ComponentType.SequenceComponent)), 5.seconds)
            ctx.pipeToSelf(akkaLocF) {
              case Failure(_)       => ProcessRegistrationFailed(pid, replyTo)
              case Success(None)    => ProcessRegistrationFailed(pid, replyTo)
              case Success(Some(_)) => ProcessRegistered(pid, replyTo)
            }

        }
        Behaviors.same

      case ProcessRegistered(pid, replyTo) =>
        debug("spawned process is registered with location service", Map("pid" -> pid))
        replyTo ! Spawned
        behavior(state.finishRegistration(pid))

      case ProcessRegistrationFailed(pid, replyTo) =>
        error(
          "could not get registration confirmation from spawned process within given time. " +
            "killing the process",
          Map("pid" -> pid)
        )
        replyTo ! Failed("could not get registration confirmation from spawned process within given time")
        killProcess(pid)
        behavior(state.failRegistration(pid))

      case KillAllProcesses =>
        debug("killing all processes")
        (state.registeredProcesses ++ state.registeringProcesses)
          .foreach(killProcess)
        behavior(AgentState.empty)
    }
  }

  private def killProcess(pid: Long): Boolean = {
    ProcessHandle
      .of(pid)
      .map(p => p.destroyForcibly())
      .asScala
      .getOrElse(false)
  }

  private def runCommand(spawnCommand: SpawnCommand, output: ProcessOutput): Either[Failed, Long] = {
    Try {
      val processBuilder = new ProcessBuilder(spawnCommand.strings: _*)
      debug(s"starting command", Map("command" -> processBuilder.command()))
      val process = processBuilder.start()
      output.attachProcess(process, spawnCommand.prefix)
      debug(s"new process spawned", Map("pid" -> process.pid()))
      process.pid()
    }.toEither.left.map {
      case NonFatal(err) =>
        error("command failed to run", map = Map("command" -> spawnCommand), ex = err)
        Failed(err.getMessage)
    }
  }
}

object AgentActor {
  case class AgentState(registeredProcesses: Set[Long], registeringProcesses: Set[Long]) {
    def withNewProcess(pid: Long): AgentState = copy(registeringProcesses = registeringProcesses + pid)
    def finishRegistration(pid: Long): AgentState = copy(
      registeredProcesses = registeringProcesses + pid,
      registeringProcesses = registeringProcesses - pid
    )
    def failRegistration(pid: Long): AgentState = copy(
      registeringProcesses = registeringProcesses - pid
    )
  }
  object AgentState {
    val empty: AgentState = AgentState(Set.empty, Set.empty)
  }
}
