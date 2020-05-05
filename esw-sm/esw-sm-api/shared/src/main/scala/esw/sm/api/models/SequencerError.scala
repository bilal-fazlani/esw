package esw.sm.api.models

sealed trait SequencerError extends Throwable {
  def msg: String
}

sealed trait AgentError extends SequencerError

object SequenceManagerError {
  case class LocationServiceError(msg: String)         extends AgentError
  case class SpawnSequenceComponentFailed(msg: String) extends AgentError

  case class LoadScriptError(msg: String) extends SequencerError
  case class SequencerNotIdle(obsMode: String) extends SequencerError {
    override def msg: String = s"Sequencers for $obsMode are already executing another sequence"
  }
}