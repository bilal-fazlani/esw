package esw.gateway.api.clients

import csw.location.models.ComponentId
import esw.gateway.api.protocol.{PostRequest, WebsocketRequest}
import esw.ocs.api.SequencerApi
import esw.ocs.api.client.SequencerClient
import msocket.api.Transport

import scala.concurrent.ExecutionContext

class SequencerClientFactory(postTransport: Transport[PostRequest], websocketTransport: Transport[WebsocketRequest])(
    implicit ec: ExecutionContext
) {

  import esw.gateway.api.codecs.GatewayCodecs._

  def make(componentId: ComponentId): SequencerApi = new SequencerClient(
    postTransport.contraMap(PostRequest.SequencerCommand(componentId, _)),
    websocketTransport.contraMap(WebsocketRequest.SequencerCommand(componentId, _))
  )
}