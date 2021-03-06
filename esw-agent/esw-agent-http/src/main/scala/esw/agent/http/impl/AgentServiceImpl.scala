package esw.agent.http.impl

import java.nio.file.Path

import akka.actor.typed.ActorSystem
import csw.location.api.scaladsl.LocationService
import csw.prefix.models.Prefix
import esw.agent.api.SpawnResponse
import esw.agent.client.AgentClient
import esw.agent.http.api.AgentService

import scala.concurrent.{ExecutionContext, Future}

class AgentServiceImpl(locationService: LocationService)(implicit actorSystem: ActorSystem[_]) extends AgentService {

  private implicit val ec: ExecutionContext    = actorSystem.executionContext
  private def agentClient(agentPrefix: Prefix) = AgentClient.make(agentPrefix, locationService)

  override def spawnSequenceManager(
      agentPrefix: Prefix,
      obsModeConfigPath: Path,
      isConfigLocal: Boolean,
      version: Option[String]
  ): Future[SpawnResponse] =
    agentClient(agentPrefix).flatMap(_.spawnSequenceManager(obsModeConfigPath, isConfigLocal, version))

  override def spawnSequenceComponent(agentPrefix: Prefix, prefix: Prefix, version: Option[String]): Future[SpawnResponse] =
    agentClient(agentPrefix).flatMap(_.spawnSequenceComponent(prefix, version))
}
