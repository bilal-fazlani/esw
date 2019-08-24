package esw.gateway.server2

import akka.http.scaladsl.server.StandardRoute
import akka.util.Timeout
import esw.gateway.api.codecs.RestlessCodecs
import esw.gateway.api.messages.{PostRequest, WebsocketRequest}
import esw.gateway.api.{AlarmApi, CommandApi, EventApi}
import esw.gateway.impl.{AlarmImpl, CommandImpl, EventImpl}
import esw.http.core.wiring.{HttpService, ServerWiring}
import mscoket.impl.RoutesFactory
import msocket.api.{PostHandler, WebsocketHandler}

import scala.concurrent.duration.DurationLong

class GatewayWiring(_port: Option[Int] = None) extends RestlessCodecs {
  lazy val wiring = new ServerWiring(_port)
  import wiring._
  import cswCtx.actorRuntime.{ec, mat}
  import cswCtx.{actorRuntime, _}
  implicit val timeout: Timeout = 10.seconds

  lazy val alarmApi: AlarmApi     = new AlarmImpl(alarmService)
  lazy val eventApi: EventApi     = new EventImpl(eventService, eventSubscriberUtil)
  lazy val commandApi: CommandApi = new CommandImpl(componentFactory.commandService)

  lazy val httpHandler: PostHandler[PostRequest, StandardRoute] =
    new PostHandlerImpl(alarmApi, commandApi, eventApi)
  lazy val websocketHandler: WebsocketHandler[WebsocketRequest] =
    new WebsocketHandlerImpl(commandApi, eventApi)

  lazy val routesFactory: RoutesFactory[PostRequest, WebsocketRequest] = new RoutesFactory(httpHandler, websocketHandler)
  lazy val httpService                                                 = new HttpService(logger, locationService, routesFactory.route, settings, actorRuntime)
}
