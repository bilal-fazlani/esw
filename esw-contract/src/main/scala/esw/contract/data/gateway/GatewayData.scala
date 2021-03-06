package esw.contract.data.gateway

import csw.alarm.models.AlarmSeverity
import csw.alarm.models.Key.AlarmKey
import csw.contract.data.command.CommandData
import csw.location.api.models.{ComponentId, ComponentType}
import csw.logging.models.{Level, LogMetadata}
import csw.params.events.{EventKey, EventName, ObserveEvent, SystemEvent}
import csw.prefix.models.Subsystem
import esw.contract.data.sequencer.SequencerData
import esw.gateway.api.protocol.PostRequest._
import esw.gateway.api.protocol.WebsocketRequest.{Subscribe, SubscribeWithPattern}
import esw.gateway.api.protocol.{
  EmptyEventKeys,
  EventServerUnavailable,
  InvalidComponent,
  InvalidMaxFrequency,
  PostRequest,
  SetAlarmSeverityFailure,
  WebsocketRequest
}

trait GatewayData extends CommandData with SequencerData {
  val componentId: ComponentId = ComponentId(prefix, ComponentType.HCD)

  val eventName: EventName       = EventName("offline")
  val observeEvent: ObserveEvent = ObserveEvent(prefix, eventName)
  val systemEvent: SystemEvent   = SystemEvent(prefix, eventName)
  val eventKey: EventKey         = EventKey(prefix, eventName)

  val logMetadata = LogMetadata(Level.INFO, Level.DEBUG, Level.INFO, Level.ERROR)

  val postComponentCommand: PostRequest.ComponentCommand = PostRequest.ComponentCommand(componentId, observeValidate)
  val postSequencerCommand: PostRequest.SequencerCommand = PostRequest.SequencerCommand(componentId, prepend)
  val publishEvent: PublishEvent                         = PublishEvent(observeEvent)
  val getEvent: GetEvent                                 = GetEvent(Set(eventKey))
  val alarmKey: AlarmKey                                 = AlarmKey(prefix, "someAlarm")
  val setAlarmSeverity: SetAlarmSeverity                 = SetAlarmSeverity(alarmKey, AlarmSeverity.Okay)
  val log: Log                                           = Log(prefix, Level.DEBUG, "message", Map("additional-info" -> 45))
  val setLogLevel: SetLogLevel                           = SetLogLevel(componentId, Level.ERROR)
  val getLogMetadata: GetLogMetadata                     = GetLogMetadata(componentId)

  val websocketComponentCommand: WebsocketRequest.ComponentCommand = WebsocketRequest.ComponentCommand(componentId, queryFinal)
  val websocketSequencerCommand: WebsocketRequest.SequencerCommand =
    WebsocketRequest.SequencerCommand(componentId, sequencerQueryFinal)
  val subscribe: Subscribe                       = Subscribe(Set(eventKey), Some(10))
  val subscribeWithPattern: SubscribeWithPattern = SubscribeWithPattern(Subsystem.CSW, Some(10), "[a-b]*")

  val invalidComponent: InvalidComponent               = InvalidComponent("invalid component")
  val emptyEventKeys: EmptyEventKeys                   = EmptyEventKeys()
  val eventServerUnavailable: EventServerUnavailable   = EventServerUnavailable()
  val invalidMaxFrequency: InvalidMaxFrequency         = InvalidMaxFrequency()
  val setAlarmSeverityFailure: SetAlarmSeverityFailure = SetAlarmSeverityFailure("alarm fail")
}
