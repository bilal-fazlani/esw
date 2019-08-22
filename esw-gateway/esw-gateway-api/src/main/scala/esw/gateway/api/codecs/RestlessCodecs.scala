package esw.gateway.api.codecs

import com.github.ghik.silencer.silent
import csw.alarm.codecs.AlarmCodecs
import csw.alarm.models.Key.AlarmKey
import csw.location.models.codecs.LocationCodecs
import csw.params.core.formats.{CodecHelpers, ParamCodecs}
import csw.params.events.EventKey
import esw.gateway.api.messages.GatewayHttpRequest._
import esw.gateway.api.messages.GatewayWebsocketRequest.{QueryFinal, Subscribe, SubscribeCurrentState, SubscribeWithPattern}
import esw.gateway.api.messages._
import io.bullet.borer.Codec
import io.bullet.borer.derivation.MapBasedCodecs.deriveCodec

trait RestlessCodecs extends ParamCodecs with LocationCodecs with AlarmCodecs {

  implicit def eventErrorCodec[T <: EventError]: Codec[T] = eventErrorCodecValue.asInstanceOf[Codec[T]]
  lazy val eventErrorCodecValue: Codec[EventError] = {
    @silent implicit lazy val emptyEventKeysCodec: Codec[EmptyEventKeys] = deriveCodec[EmptyEventKeys]
    invalidMaxFrequencyCodec
    deriveCodec[EventError]
  }

  implicit def commandErrorMsgCodec[T <: CommandError]: Codec[T] = eventErrorCodecValue.asInstanceOf[Codec[T]]
  lazy val commandErrorMsgCodecValue: Codec[CommandError] = {
    @silent implicit lazy val invalidComponentCodec: Codec[InvalidComponent] = deriveCodec[InvalidComponent]
    invalidMaxFrequencyCodec
    deriveCodec[CommandError]
  }

  implicit lazy val invalidMaxFrequencyCodec: Codec[InvalidMaxFrequency]         = deriveCodec[InvalidMaxFrequency]
  implicit lazy val setAlarmSeverityFailureCodec: Codec[SetAlarmSeverityFailure] = deriveCodec[SetAlarmSeverityFailure]

  implicit def gatewayHttpRequestCodec[T <: GatewayHttpRequest]: Codec[T] = gatewayHttpRequestValue.asInstanceOf[Codec[T]]
  lazy val gatewayHttpRequestValue: Codec[GatewayHttpRequest] = {
    @silent implicit lazy val commandRequestCodec: Codec[CommandRequest]     = deriveCodec[CommandRequest]
    @silent implicit lazy val publishEventCodec: Codec[PublishEvent]         = deriveCodec[PublishEvent]
    @silent implicit lazy val getEventCodec: Codec[GetEvent]                 = deriveCodec[GetEvent]
    @silent implicit lazy val setAlarmSeverityCodec: Codec[SetAlarmSeverity] = deriveCodec[SetAlarmSeverity]
    deriveCodec[GatewayHttpRequest]
  }
  implicit lazy val commandActionCodec: Codec[CommandAction] = CodecHelpers.enumCodec[CommandAction]

  implicit def gatewayWebsocketRequestCodec[T <: GatewayWebsocketRequest]: Codec[T] =
    webSocketRequestCodecValue.asInstanceOf[Codec[T]]
  lazy val webSocketRequestCodecValue: Codec[GatewayWebsocketRequest] = {
    @silent implicit lazy val queryFinalCodec: Codec[QueryFinal] = deriveCodec[QueryFinal]
    @silent implicit lazy val subscribeCodec: Codec[Subscribe]   = deriveCodec[Subscribe]
    @silent implicit lazy val subscribeWithPatternCodec: Codec[SubscribeWithPattern] =
      deriveCodec[SubscribeWithPattern]
    @silent implicit lazy val subscribeCurrentStateCodec: Codec[SubscribeCurrentState] =
      deriveCodec[SubscribeCurrentState]

    deriveCodec[GatewayWebsocketRequest]
  }

  //Todo: move to csw
  implicit lazy val eventKeyCodec: Codec[EventKey] = deriveCodec[EventKey]
  implicit lazy val alarmKeyCodec: Codec[AlarmKey] = deriveCodec[AlarmKey]
}
