package esw.sm.impl.core

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import csw.location.api.models.ComponentType.Sequencer
import csw.location.api.models.Connection.HttpConnection
import csw.location.api.models.{AkkaLocation, ComponentId}
import csw.prefix.models.Subsystem.ESW
import csw.prefix.models.{Prefix, Subsystem}
import esw.commons.extensions.FutureEitherExt.FutureEitherOps
import esw.commons.utils.location.EswLocationError.RegistrationListingFailed
import esw.commons.utils.location.LocationServiceUtil
import esw.sm.api.SequenceManagerState
import esw.sm.api.SequenceManagerState._
import esw.sm.api.actor.messages.SequenceManagerMsg._
import esw.sm.api.actor.messages.{SequenceManagerIdleMsg, SequenceManagerMsg}
import esw.sm.api.models.CommonFailure.ConfigurationMissing
import esw.sm.api.models.ConfigureResponse.ConflictingResourcesWithRunningObsMode
import esw.sm.api.models.StartSequencerResponse.{AlreadyRunning, Started}
import esw.sm.api.models._
import esw.sm.impl.config.{ObsModeConfig, Resources, SequenceManagerConfig}
import esw.sm.impl.utils.SequencerUtil

import scala.async.Async.{async, await}
import scala.concurrent.Future
import scala.reflect.ClassTag

class SequenceManagerBehavior(
    config: SequenceManagerConfig,
    locationServiceUtil: LocationServiceUtil,
    sequencerUtil: SequencerUtil
)(implicit val actorSystem: ActorSystem[_]) {
  import actorSystem.executionContext

  private val sequencerStartRetries = config.sequencerStartRetries

  def setup: Behavior[SequenceManagerMsg] = Behaviors.setup { ctx => idle(ctx.self) }

  private def idle(self: ActorRef[SequenceManagerMsg]): Behavior[SequenceManagerMsg] = {
    receive[SequenceManagerIdleMsg](Idle) {
      case Configure(observingMode, replyTo)                    => configure(observingMode, self, replyTo)
      case Cleanup(observingMode, replyTo)                      => cleanup(observingMode, self, replyTo)
      case StartSequencer(subsystem, observingMode, replyTo)    => startSequencer(subsystem, observingMode, self, replyTo)
      case ShutdownSequencer(subsystem, observingMode, replyTo) => shutDownSequencer(subsystem, observingMode, self, replyTo)
      case ShutdownAllSequencers(replyTo)                       => shutDownAllSequencers(self, replyTo)
      case RestartSequencer(subsystem, observingMode, replyTo)  => restartSequencer(subsystem, observingMode, self, replyTo)
    }
  }

  private def configure(
      obsMode: String,
      self: ActorRef[SequenceManagerMsg],
      replyTo: ActorRef[ConfigureResponse]
  ): Behavior[SequenceManagerMsg] = {
    val runningObsModesF = getRunningObsModes.flatMapToAdt(
      configuredObsModes => configureResources(obsMode, configuredObsModes),
      error => CommonFailure.LocationServiceError(error.msg)
    )

    runningObsModesF.map(self ! ConfigurationResponseInternal(_))
    configuring(self, replyTo)
  }

  // start all the required sequencers associated with obs mode,
  // if requested resources does not conflict with existing running observations
  private def configureResources(requestedObsMode: String, runningObsModes: Set[String]): Future[ConfigureResponse] =
    async {
      config.obsModeConfig(requestedObsMode) match {
        case Some(ObsModeConfig(resources, _)) if checkConflicts(resources, runningObsModes) =>
          ConflictingResourcesWithRunningObsMode(runningObsModes)
        case Some(ObsModeConfig(_, sequencers)) =>
          await(sequencerUtil.startSequencers(requestedObsMode, sequencers, sequencerStartRetries))
        case None => ConfigurationMissing(requestedObsMode)
      }
    }

  // ignoring failure of getResources as config should never be absent for running obsModes
  private def checkConflicts(requiredResources: Resources, runningObsModes: Set[String]) =
    requiredResources.conflictsWithAny(runningObsModes.map(getResources))

  private def getResources(obsMode: String): Resources = config.resources(obsMode).get

  // Configuration is in progress, waiting for ConfigurationResponseInternal message
  // Within this period, reject all the other messages except common messages
  private def configuring(
      self: ActorRef[SequenceManagerMsg],
      replyTo: ActorRef[ConfigureResponse]
  ): Behavior[SequenceManagerMsg] =
    receive[ConfigurationResponseInternal](Configuring)(msg => replyAndGoToIdle(self, replyTo, msg.res))

  private def cleanup(
      obsMode: String,
      self: ActorRef[SequenceManagerMsg],
      replyTo: ActorRef[CleanupResponse]
  ): Behavior[SequenceManagerMsg] = {
    val cleanupResponseF =
      config
        .sequencers(obsMode)
        .map(sequencerUtil.shutdownSequencers(_, obsMode))
        .getOrElse(Future.successful(ConfigurationMissing(obsMode)))

    cleanupResponseF.map(self ! CleanupResponseInternal(_))
    cleaningUp(self, replyTo)
  }

  // Clean up is in progress, waiting for CleanupResponseInternal message
  // Within this period, reject all the other messages except common messages
  private def cleaningUp(self: ActorRef[SequenceManagerMsg], replyTo: ActorRef[CleanupResponse]): Behavior[SequenceManagerMsg] =
    receive[CleanupResponseInternal](CleaningUp)(msg => replyAndGoToIdle(self, replyTo, msg.res))

  private def startSequencer(
      subsystem: Subsystem,
      obsMode: String,
      self: ActorRef[SequenceManagerMsg],
      replyTo: ActorRef[StartSequencerResponse]
  ): Behavior[SequenceManagerMsg] = {
    // resolve is not needed here. Find should suffice
    // no concurrent start sequencer or configure is allowed
    locationServiceUtil
      .find(HttpConnection(ComponentId(Prefix(subsystem, obsMode), Sequencer)))
      .flatMap {
        case Left(_)         => startSequencer(subsystem, obsMode)
        case Right(location) => Future.successful(AlreadyRunning(location.connection.componentId))
      }
      .map(self ! StartSequencerResponseInternal(_))

    startingSequencer(self, replyTo)
  }

  private def startSequencer(subsystem: Subsystem, obsMode: String): Future[StartSequencerResponse] =
    sequencerUtil
      .startSequencer(subsystem, obsMode, sequencerStartRetries)
      .mapToAdt(akkaLocation => Started(akkaLocation.connection.componentId), identity)

  // Starting sequencer is in progress, waiting for StartSequencerResponseInternal message
  // Within this period, reject all the other messages except common messages
  private def startingSequencer(
      self: ActorRef[SequenceManagerMsg],
      replyTo: ActorRef[StartSequencerResponse]
  ): Behavior[SequenceManagerMsg] =
    receive[StartSequencerResponseInternal](StartingSequencer)(msg => replyAndGoToIdle(self, replyTo, msg.res))

  private def shutDownSequencer(
      subsystem: Subsystem,
      obsMode: String,
      self: ActorRef[SequenceManagerMsg],
      replyTo: ActorRef[ShutdownSequencerResponse]
  ): Behavior[SequenceManagerMsg] = {
    val eventualResponseF: Future[ShutdownSequencerResponse] = sequencerUtil
      .shutdownSequencer(subsystem, obsMode)
      .mapToAdt(identity, identity)

    eventualResponseF.map(self ! ShutdownSequencerResponseInternal(_))
    shuttingDownSequencer(self, replyTo)
  }

  // Shutdown sequencer is in progress, waiting for ShutdownSequencerResponseInternal message
  // Within this period, reject all the other messages except common messages
  private def shuttingDownSequencer(
      self: ActorRef[SequenceManagerMsg],
      replyTo: ActorRef[ShutdownSequencerResponse]
  ): Behavior[SequenceManagerMsg] =
    receive[ShutdownSequencerResponseInternal](ShuttingDownSequencer)(msg => replyAndGoToIdle(self, replyTo, msg.res))

  private def restartSequencer(
      subsystem: Subsystem,
      obsMode: String,
      self: ActorRef[SequenceManagerMsg],
      replyTo: ActorRef[RestartSequencerResponse]
  ): Behavior[SequenceManagerMsg] = {
    val eventualResponseF: Future[RestartSequencerResponse] = sequencerUtil
      .restartSequencer(subsystem, obsMode, sequencerStartRetries)
      .mapToAdt(akkaLocation => RestartSequencerResponse.Success(akkaLocation.connection.componentId), identity)

    eventualResponseF.map(self ! RestartSequencerResponseInternal(_))
    restartingSequencer(self, replyTo)
  }

  private def restartingSequencer(
      self: ActorRef[SequenceManagerMsg],
      replyTo: ActorRef[RestartSequencerResponse]
  ): Behavior[SequenceManagerMsg] = {
    receive[RestartSequencerResponseInternal](RestartingSequencer)(msg => replyAndGoToIdle(self, replyTo, msg.res))
  }

  private def shutDownAllSequencers(
      self: ActorRef[SequenceManagerMsg],
      replyTo: ActorRef[ShutdownAllSequencersResponse]
  ): Behavior[SequenceManagerMsg] = {
    val eventualResponseF: Future[ShutdownAllSequencersResponse] = sequencerUtil.shutdownAllSequencers()

    eventualResponseF.map(self ! ShutdownAllSequencersResponseInternal(_))
    shuttingDownAllSequencers(self, replyTo)
  }

  private def shuttingDownAllSequencers(
      self: ActorRef[SequenceManagerMsg],
      replyTo: ActorRef[ShutdownAllSequencersResponse]
  ): Behavior[SequenceManagerMsg] =
    receive[ShutdownAllSequencersResponseInternal](ShuttingDownAllSequencers)(msg => replyAndGoToIdle(self, replyTo, msg.res))

  private def replyAndGoToIdle[T](self: ActorRef[SequenceManagerMsg], replyTo: ActorRef[T], msg: T) = {
    replyTo ! msg
    idle(self)
  }

  private def receive[T <: SequenceManagerMsg: ClassTag](
      state: SequenceManagerState
  )(handler: T => Behavior[SequenceManagerMsg]): Behavior[SequenceManagerMsg] =
    Behaviors.receiveMessage {
      case msg: CommonMessage => handleCommon(msg, state); Behaviors.same
      case msg: T             => handler(msg)
      case _                  => Behaviors.unhandled
    }

  private def handleCommon(msg: CommonMessage, currentState: SequenceManagerState): Unit =
    msg match {
      case GetRunningObsModes(replyTo)      => runningObsModesResponse.foreach(replyTo ! _)
      case GetSequenceManagerState(replyTo) => replyTo ! currentState
    }

  private def runningObsModesResponse =
    getRunningObsModes.mapToAdt(
      obsModes => GetRunningObsModesResponse.Success(obsModes),
      error => GetRunningObsModesResponse.Failed(error.msg)
    )

  // get the component name of all the top level sequencers i.e. ESW sequencers
  private def getRunningObsModes: Future[Either[RegistrationListingFailed, Set[String]]] =
    locationServiceUtil.listAkkaLocationsBy(ESW, Sequencer).mapRight(_.map(getObsMode).toSet)

  // componentName = obsMode, as per convention, sequencer uses obs mode to form component name
  private def getObsMode(akkaLocation: AkkaLocation): String = akkaLocation.prefix.componentName
}
