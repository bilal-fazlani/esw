package esw.sm.impl.utils

import akka.actor.typed.ActorSystem
import csw.location.api.models.ComponentType.Sequencer
import csw.location.api.models.{AkkaLocation, ComponentId, Location}
import csw.prefix.models.Subsystem.ESW
import csw.prefix.models.{Prefix, Subsystem}
import esw.commons.extensions.FutureEitherExt.FutureEitherOps
import esw.commons.extensions.ListEitherExt.ListEitherOps
import esw.commons.utils.FutureUtils
import esw.commons.utils.location.{EswLocationError, LocationServiceUtil}
import esw.ocs.api.actor.client.SequencerApiFactory
import esw.ocs.api.models.ObsMode
import esw.ocs.api.protocol.ScriptError
import esw.ocs.api.protocol.SequenceComponentResponse.{SequencerLocation, Unhandled}
import esw.ocs.api.{SequenceComponentApi, SequencerApi}
import esw.sm.api.protocol.CommonFailure.LocationServiceError
import esw.sm.api.protocol.ConfigureResponse.{FailedToStartSequencers, Success}
import esw.sm.api.protocol.ShutdownSequencersPolicy.{AllSequencers, ObsModeSequencers, SingleSequencer, SubsystemSequencers}
import esw.sm.api.protocol.StartSequencerResponse.LoadScriptError
import esw.sm.api.protocol._
import esw.sm.impl.config.Sequencers

import scala.concurrent.{ExecutionContext, Future}

class SequencerUtil(locationServiceUtil: LocationServiceUtil, sequenceComponentUtil: SequenceComponentUtil)(implicit
    actorSystem: ActorSystem[_]
) {
  implicit private val ec: ExecutionContext = actorSystem.executionContext

  // spawn the sequencer on available SequenceComponent
  def startSequencer(
      subSystem: Subsystem,
      obsMode: ObsMode,
      retryCount: Int
  ): Future[Either[StartSequencerResponse.Failure, AkkaLocation]] =
    sequenceComponentUtil
      .getAvailableSequenceComponent(subSystem)
      .flatMap {
        case Right(seqCompApi)         => loadScript(subSystem, obsMode, seqCompApi, retryCount)
        case Left(_) if retryCount > 0 => startSequencer(subSystem, obsMode, retryCount - 1)
        case Left(e)                   => Future.successful(Left(e))
      }

  def startSequencers(obsMode: ObsMode, requiredSequencers: Sequencers, retryCount: Int): Future[ConfigureResponse] = {
    val masterSequencerId = ComponentId(Prefix(ESW, obsMode.name), Sequencer)

    val startSequencerResponses = sequential(requiredSequencers.subsystems)(startSequencer(_, obsMode, retryCount))
    startSequencerResponses.mapToAdt(_ => Success(masterSequencerId), e => FailedToStartSequencers(e.map(_.msg).toSet))
  }

  def restartSequencer(subSystem: Subsystem, obsMode: ObsMode): Future[RestartSequencerResponse] =
    locationServiceUtil
      .findSequencer(subSystem, obsMode)
      .flatMapToAdt(restartSequencer, e => LocationServiceError(e.msg))

  private def restartSequencer(akkaLocation: AkkaLocation): Future[RestartSequencerResponse] =
    createSequencerClient(akkaLocation).getSequenceComponent
      .flatMap(sequenceComponentUtil.restartScript(_).map {
        case SequencerLocation(location) => RestartSequencerResponse.Success(location.connection.componentId)
        case error: ScriptError          => LoadScriptError(error.msg)
        case Unhandled(_, _, msg)        => LoadScriptError(msg) // restart is unhandled in idle or shutting down state
      })

  def shutdownSequencers(policy: ShutdownSequencersPolicy): Future[ShutdownSequencersResponse] =
    policy match {
      case SingleSequencer(subsystem, obsMode) => shutdownSequencersAndHandleErrors(getSequencer(subsystem, obsMode))
      case SubsystemSequencers(subsystem)      => shutdownSequencersAndHandleErrors(getSubsystemSequencers(subsystem))
      case ObsModeSequencers(obsMode)          => shutdownSequencersAndHandleErrors(getObsModeSequencers(obsMode))
      case AllSequencers                       => shutdownSequencersAndHandleErrors(getAllSequencers)
    }

  private def getSequencer(subsystem: Subsystem, obsMode: ObsMode) =
    locationServiceUtil.findSequencer(subsystem, obsMode).mapRight(List(_))
  private def getSubsystemSequencers(subsystem: Subsystem) = locationServiceUtil.listAkkaLocationsBy(subsystem, Sequencer)
  private def getObsModeSequencers(obsMode: ObsMode)       = locationServiceUtil.listAkkaLocationsBy(obsMode.name, Sequencer)
  private def getAllSequencers                             = locationServiceUtil.listAkkaLocationsBy(Sequencer)

  private def shutdownSequencersAndHandleErrors(sequencers: Future[Either[EswLocationError, List[AkkaLocation]]]) =
    sequencers.flatMapRight(unloadScripts).mapToAdt(identity, locationErrorToShutdownSequencersResponse)

  private def locationErrorToShutdownSequencersResponse(err: EswLocationError) =
    err match {
      case _: EswLocationError.LocationNotFound => ShutdownSequencersResponse.Success
      case e: EswLocationError                  => LocationServiceError(e.msg)
    }

  private def loadScript(subSystem: Subsystem, obsMode: ObsMode, seqCompApi: SequenceComponentApi, retryCount: Int) =
    seqCompApi
      .loadScript(subSystem, obsMode)
      .flatMap {
        case SequencerLocation(location)                           => Future.successful(Right(location))
        case _: ScriptError.LocationServiceError if retryCount > 0 => startSequencer(subSystem, obsMode, retryCount - 1)
        case error: ScriptError.LoadingScriptFailed                => Future.successful(Left(LoadScriptError(error.msg)))
        case Unhandled(_, _, _) if retryCount > 0                  => startSequencer(subSystem, obsMode, retryCount - 1)
      }

  // get sequence component from Sequencer and unload sequencer script
  private def unloadScript(sequencerLocation: AkkaLocation) =
    createSequencerClient(sequencerLocation).getSequenceComponent
      .flatMap(sequenceComponentUtil.unloadScript)
      .map(_ => ShutdownSequencersResponse.Success)

  private def unloadScripts(sequencerLocations: List[AkkaLocation]): Future[ShutdownSequencersResponse.Success.type] =
    Future.traverse(sequencerLocations)(unloadScript).map(_ => ShutdownSequencersResponse.Success)

  // Created in order to mock the behavior of sequencer API availability for unit test
  private[sm] def createSequencerClient(location: Location): SequencerApi = SequencerApiFactory.make(location)

  private def sequential[T, L, R](i: List[T])(f: T => Future[Either[L, R]]) = FutureUtils.sequential(i)(f).map(_.sequence)
}
