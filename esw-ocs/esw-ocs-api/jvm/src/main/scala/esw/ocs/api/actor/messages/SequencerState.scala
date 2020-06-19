package esw.ocs.api.actor.messages

import csw.command.client.messages.sequencer.SequencerMsg
import enumeratum.{Enum, EnumEntry}
import esw.ocs.api.actor.messages.SequencerMessages._
import esw.ocs.api.codecs.OcsAkkaSerializable

import scala.collection.immutable.IndexedSeq

sealed trait SequencerState[+T <: SequencerMsg] extends EnumEntry with OcsAkkaSerializable
object SequencerState extends Enum[SequencerState[SequencerMsg]] {

  override def values: IndexedSeq[SequencerState[SequencerMsg]] = findValues

  case object Idle             extends SequencerState[IdleMessage]
  case object Loaded           extends SequencerState[SequenceLoadedMessage]
  case object InProgress       extends SequencerState[InProgressMessage]
  case object Offline          extends SequencerState[OfflineMessage]
  case object GoingOnline      extends SequencerState[GoingOnlineMessage]
  case object GoingOffline     extends SequencerState[GoingOfflineMessage]
  case object AbortingSequence extends SequencerState[AbortSequenceMessage]
  case object Stopping         extends SequencerState[StopMessage]
  case object Submitting       extends SequencerState[SubmitMessage]
  case object Starting         extends SequencerState[StartingMessage]
}
