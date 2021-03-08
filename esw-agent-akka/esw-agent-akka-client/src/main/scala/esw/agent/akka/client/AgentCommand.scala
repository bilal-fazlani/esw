package esw.agent.akka.client

import akka.actor.typed.ActorRef
import csw.location.api.models.ComponentType.{SequenceComponent, Service}
import csw.location.api.models.Connection.AkkaConnection
import csw.location.api.models._
import csw.prefix.models.Prefix
import csw.prefix.models.Subsystem.ESW
import esw.agent.akka.client.models.ContainerConfig
import esw.agent.service.api._
import esw.agent.service.api.models.{KillResponse, SpawnContainersResponse, SpawnResponse}

import java.nio.file.Path

sealed trait AgentCommand
sealed trait AgentRemoteCommand extends AgentCommand with AgentAkkaSerializable

object AgentCommand {

  sealed trait SpawnCommand extends AgentRemoteCommand {
    def replyTo: ActorRef[SpawnResponse]
    def commandArgs(extraArgs: List[String] = List.empty): List[String]
    def prefix: Prefix
    def connection: Connection

    def componentId: ComponentId = connection.componentId
  }

  object SpawnCommand {
    case class SpawnSequenceComponent(
        replyTo: ActorRef[SpawnResponse],
        agentPrefix: Prefix,
        componentName: String,
        version: Option[String],
        simulation: Boolean = false
    ) extends SpawnCommand {
      override val prefix: Prefix             = Prefix(agentPrefix.subsystem, componentName)
      override val connection: AkkaConnection = AkkaConnection(ComponentId(prefix, SequenceComponent))

      private val sim = if (simulation) List("--simulation") else List.empty
      override def commandArgs(extraArgs: List[String]): List[String] =
        List("seqcomp", "-s", prefix.subsystem.name, "-n", componentName) ++ extraArgs ++ sim
    }

    case class SpawnSequenceManager(
        replyTo: ActorRef[SpawnResponse],
        obsModeConfigPath: Path,
        isConfigLocal: Boolean,
        version: Option[String],
        simulation: Boolean = false
    ) extends SpawnCommand {
      override val prefix: Prefix             = Prefix(ESW, "sequence_manager")
      override val connection: AkkaConnection = AkkaConnection(ComponentId(prefix, Service))
      private val command                     = List("start", "-o", obsModeConfigPath.toString)
      private val sim                         = if (simulation) List("--simulation") else List.empty

      override def commandArgs(extraArgs: List[String]): List[String] = {
        val args = if (isConfigLocal) command :+ "-l" else command
        args ++ extraArgs ++ sim
      }
    }

    case class SpawnContainer(
        replyTo: ActorRef[SpawnResponse],
        containerComponentId: ComponentId,
        containerConfig: ContainerConfig
    ) extends SpawnCommand {
      override val prefix: Prefix             = containerComponentId.prefix
      override val connection: AkkaConnection = AkkaConnection(containerComponentId)
      private val command                     = List(containerConfig.configFilePath.toString)

      override def commandArgs(extraArgs: List[String]): List[String] = {
        var args = command
        if (containerConfig.mode == "Standalone") args = "--standalone" :: args
        if (containerConfig.isConfigLocal) args = "--local" :: args
        args ++ extraArgs
      }
    }
  }

  case class SpawnContainers(replyTo: ActorRef[SpawnContainersResponse], hostConfigPath: String, isConfigLocal: Boolean)
      extends AgentRemoteCommand

  case class KillComponent(replyTo: ActorRef[KillResponse], location: Location) extends AgentRemoteCommand
}
