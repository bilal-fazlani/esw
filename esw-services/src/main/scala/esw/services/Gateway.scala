package esw.services

import akka.actor.CoordinatedShutdown.ActorSystemTerminateReason
import csw.services.utils.ColoredConsole.GREEN
import esw.gateway.server.{GatewayMain, GatewayWiring}
import esw.services.internal.ManagedService

import java.nio.file.{Files, Path}
import scala.io.Source
import scala.util.Using

object Gateway {

  def service(enable: Boolean, maybeCommandRoleConfigPath: Option[Path]): ManagedService[GatewayWiring] = {
    ManagedService(
      "gateway",
      enable,
      () => startGateway(getConfig(maybeCommandRoleConfigPath)),
      stopGateway
    )
  }

  private def getConfig(maybeCommandRoleConfigPath: Option[Path]): Path = {
    maybeCommandRoleConfigPath.getOrElse({
      val tempConfigPath = Files.createTempFile("commandRoles-", ".conf")
      Using(Source.fromResource("commandRoles.conf")) { reader =>
        Files.write(tempConfigPath, reader.mkString.getBytes)
      }
      tempConfigPath.toFile.deleteOnExit()
      GREEN.println("Using default command role config for gateway.")
      tempConfigPath
    })
  }

  private def startGateway(commandRoleConfigPath: Path): GatewayWiring =
    GatewayMain.start(None, local = true, commandRoleConfigPath, metricsEnabled = true, startLogging = true)

  private def stopGateway(wiring: GatewayWiring): Unit = wiring.actorRuntime.shutdown(ActorSystemTerminateReason)
}
