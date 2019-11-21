package esw.ocs.api

import akka.Done
import esw.ocs.api.protocol.{GetStatusResponse, ScriptResponse}

import scala.concurrent.Future

trait SequenceComponentApi {
  def loadScript(packageId: String, observingMode: String): Future[ScriptResponse]
  def restart(): Future[ScriptResponse]
  def unloadScript(): Future[Done]
  def status: Future[GetStatusResponse]
}
