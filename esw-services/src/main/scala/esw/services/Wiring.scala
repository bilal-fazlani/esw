package esw.services

import java.nio.file.Path

import akka.actor.typed.{ActorSystem, SpawnProtocol}
import com.typesafe.config.{Config, ConfigFactory}
import csw.config.api.scaladsl.ConfigService
import csw.config.api.{ConfigData, TokenFactory}
import csw.config.client.scaladsl.ConfigClientFactory
import csw.location.api.scaladsl.LocationService
import csw.location.client.ActorSystemFactory
import csw.location.client.scaladsl.HttpLocationServiceFactory
import csw.prefix.models.Prefix
import csw.prefix.models.Subsystem.ESW
import esw.agent.akka.app.AgentWiring
import esw.agent.service.app.AgentServiceWiring
import esw.commons.extensions.FutureExt.FutureOps
import esw.commons.utils.aas.Keycloak
import esw.commons.utils.config.ConfigServiceExt
import esw.commons.utils.files.FileUtils
import esw.constants.CommonTimeouts
import esw.gateway.server.GatewayWiring
import esw.services.apps.{Agent, AgentService, Gateway, SequenceManager}
import esw.services.cli.Command.Start
import esw.services.internal.ManagedService
import esw.sm.app.SequenceManagerWiring

class Wiring(startCmd: Start) {

  lazy implicit val actorSystem: ActorSystem[SpawnProtocol.Command] = ActorSystemFactory.remote(SpawnProtocol())
  private val systemConfig: Config                                  = ConfigFactory.load()

  private lazy val agentApp: ManagedService[AgentWiring]            = Agent.service(startCmd.agent, agentPrefix, systemConfig)
  private lazy val agentService: ManagedService[AgentServiceWiring] = AgentService.service(startCmd.agentService)
  private lazy val gatewayService: ManagedService[GatewayWiring]    = Gateway.service(startCmd.gateway, startCmd.commandRoleConfig)
  private lazy val smService: ManagedService[SequenceManagerWiring] =
    SequenceManager.service(startCmd.sequenceManager, startCmd.obsModeConfig, agentPrefixForSM, startCmd.simulation)

  lazy val serviceList = List(agentApp, agentService, gatewayService, smService)

  lazy val locationService: LocationService = HttpLocationServiceFactory.makeLocalClient(actorSystem)

  private lazy val configService: ConfigService = ConfigClientFactory.adminApi(actorSystem, locationService, tokenFactory)
  private lazy val config                       = systemConfig.getConfig("csw")
  private lazy val configAdminUsername: String  = config.getString("configAdminUsername")
  private lazy val configAdminPassword: String  = config.getString("configAdminPassword")
  private lazy val keycloak                     = new Keycloak(locationService)(actorSystem.executionContext)

  private def tokenFactory: TokenFactory =
    new TokenFactory {
      override def getToken: String = keycloak.getToken(configAdminUsername, configAdminPassword).await(CommonTimeouts.Wiring)
    }

  private val obsModeConfRemotePath = Path.of(systemConfig.getString("esw.sm.obsModeConfigPath"))
  private val configData            = ConfigData.fromString(FileUtils.readResource("smObsModeConfig.conf"))
  private lazy val configServiceExt = new ConfigServiceExt(configService)

  configServiceExt.saveConfig(obsModeConfRemotePath, configData)

  def start(): Unit = serviceList.foreach(_.start())

  def stop(): Unit = serviceList.foreach(_.stop())

  private def DefaultAgentPrefix: Prefix = Prefix(ESW, "primary")

  private def agentPrefix: Prefix = startCmd.agentPrefix.getOrElse(DefaultAgentPrefix)

  private def agentPrefixForSM: Option[Prefix] = if (startCmd.agent) Some(agentPrefix) else None
}
