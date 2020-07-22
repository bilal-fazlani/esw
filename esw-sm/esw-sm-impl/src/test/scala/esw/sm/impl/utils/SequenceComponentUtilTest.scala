package esw.sm.impl.utils

import java.net.URI

import akka.actor.typed.{ActorSystem, SpawnProtocol}
import csw.location.api.models.ComponentType._
import csw.location.api.models.Connection.AkkaConnection
import csw.location.api.models.{AkkaLocation, ComponentId}
import csw.prefix.models.Subsystem._
import csw.prefix.models.{Prefix, Subsystem}
import esw.commons.utils.location.EswLocationError.{LocationNotFound, RegistrationListingFailed}
import esw.commons.utils.location.LocationServiceUtil
import esw.ocs.api.SequenceComponentApi
import esw.ocs.api.actor.client.SequenceComponentImpl
import esw.ocs.api.models.ObsMode
import esw.ocs.api.models.SequenceComponentState.Running
import esw.ocs.api.protocol.ScriptError
import esw.ocs.api.protocol.ScriptError.LoadingScriptFailed
import esw.ocs.api.protocol.SequenceComponentResponse.{GetStatusResponse, Ok, SequencerLocation, Unhandled}
import esw.sm.api.protocol.CommonFailure.LocationServiceError
import esw.sm.api.protocol.ShutdownSequenceComponentsPolicy.{AllSequenceComponents, SingleSequenceComponent}
import esw.sm.api.protocol.StartSequencerResponse.{LoadScriptError, SequenceComponentNotAvailable, Started}
import esw.sm.api.protocol.{ConfigureResponse, ShutdownSequenceComponentResponse, StartSequencerResponse}
import esw.sm.impl.config.Sequencers
import esw.sm.impl.utils.SequenceComponentAllocator.SequencerToSequenceComponentMap
import esw.testcommons.BaseTestSuite
import org.mockito.ArgumentMatchers.{any, eq => argEq}
import org.scalatest.prop.TableDrivenPropertyChecks

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

class SequenceComponentUtilTest extends BaseTestSuite with TableDrivenPropertyChecks {
  private implicit val actorSystem: ActorSystem[SpawnProtocol.Command] = ActorSystem(SpawnProtocol(), "test-system")

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(5.seconds, 10.millis)

  private val locationServiceUtil        = mock[LocationServiceUtil]
  private val sequenceComponentAllocator = mock[SequenceComponentAllocator]
  private val sequenceComponentApi       = mock[SequenceComponentImpl]
  private val sequenceComponentUtil      = new SequenceComponentUtil(locationServiceUtil, sequenceComponentAllocator)

  override def beforeEach(): Unit = reset(locationServiceUtil, sequenceComponentAllocator, sequenceComponentApi)

  override def afterAll(): Unit = {
    actorSystem.terminate()
    actorSystem.whenTerminated.futureValue
  }

  "idleSequenceComponent" must {
    "return none if sequence component is running a sequencer | ESW-164" in {
      val mockSeqCompImpl = mock[SequenceComponentImpl]
      val seqCompUtil: SequenceComponentUtil = new SequenceComponentUtil(locationServiceUtil, sequenceComponentAllocator) {
        override private[sm] def createSequenceComponentImpl(sequenceComponentLocation: AkkaLocation): SequenceComponentImpl =
          mockSeqCompImpl
      }

      when(mockSeqCompImpl.status)
        .thenReturn(Future.successful(GetStatusResponse(Some(sequenceComponentLocation("IRIS.DarkNight")))))

      seqCompUtil.idleSequenceComponent(sequenceComponentLocation("ESW.backup")).futureValue should ===(None)
    }
  }

  "shutdown" must {
    "return success when shutdown of single sequence component is successful | ESW-338" in {
      val mockSeqCompImpl = mock[SequenceComponentImpl]
      val seqCompUtil: SequenceComponentUtil = new SequenceComponentUtil(locationServiceUtil, sequenceComponentAllocator) {
        override private[sm] def createSequenceComponentImpl(sequenceComponentLocation: AkkaLocation): SequenceComponentImpl =
          mockSeqCompImpl
      }
      val prefixStr      = "ESW.primary"
      val akkaConnection = AkkaConnection(ComponentId(Prefix(prefixStr), SequenceComponent))
      when(locationServiceUtil.find(akkaConnection))
        .thenReturn(Future.successful(Right(sequenceComponentLocation(prefixStr))))
      when(mockSeqCompImpl.shutdown()).thenReturn(Future.successful(Ok))

      val singleShutdownPolicy = SingleSequenceComponent(Prefix(prefixStr))
      seqCompUtil.shutdown(singleShutdownPolicy).futureValue should ===(ShutdownSequenceComponentResponse.Success)

      verify(locationServiceUtil).find(akkaConnection)
      verify(mockSeqCompImpl).shutdown()
    }

    "return error when location service returns error while shutting down single sequencer | ESW-338" in {
      val mockSeqCompImpl = mock[SequenceComponentImpl]
      val seqCompUtil: SequenceComponentUtil = new SequenceComponentUtil(locationServiceUtil, sequenceComponentAllocator) {
        override private[sm] def createSequenceComponentImpl(sequenceComponentLocation: AkkaLocation): SequenceComponentImpl =
          mockSeqCompImpl
      }
      val prefixStr  = "ESW.primary"
      val connection = AkkaConnection(ComponentId(Prefix(prefixStr), SequenceComponent))
      when(locationServiceUtil.find(connection))
        .thenReturn(Future.successful(Left(LocationNotFound("error"))))

      val singleShutdownPolicy = SingleSequenceComponent(Prefix(prefixStr))
      seqCompUtil.shutdown(singleShutdownPolicy).futureValue should ===(LocationServiceError("error"))

      verify(locationServiceUtil).find(connection)
      verify(mockSeqCompImpl, never).shutdown()
    }

    "return success when shutting down all sequence components is successful | ESW-346" in {

      val eswSeqCompLoc   = sequenceComponentLocation("ESW.primary")
      val irisSeqCompLoc  = sequenceComponentLocation("IRIS.primary")
      val eswSeqCompImpl  = mock[SequenceComponentImpl]
      val irisSeqCompImpl = mock[SequenceComponentImpl]

      val seqCompUtil: SequenceComponentUtil = new SequenceComponentUtil(locationServiceUtil, sequenceComponentAllocator) {
        override private[sm] def createSequenceComponentImpl(loc: AkkaLocation): SequenceComponentImpl =
          if (loc.prefix.subsystem == ESW) eswSeqCompImpl else irisSeqCompImpl
      }

      when(locationServiceUtil.listAkkaLocationsBy(SequenceComponent))
        .thenReturn(Future.successful(Right(List(eswSeqCompLoc, irisSeqCompLoc))))

      when(eswSeqCompImpl.shutdown()).thenReturn(Future.successful(Ok))
      when(irisSeqCompImpl.shutdown()).thenReturn(Future.successful(Ok))

      seqCompUtil.shutdown(AllSequenceComponents).futureValue should ===(ShutdownSequenceComponentResponse.Success)

      verify(locationServiceUtil).listAkkaLocationsBy(SequenceComponent)
      verify(eswSeqCompImpl).shutdown()
      verify(irisSeqCompImpl).shutdown()
    }

    "return error when location service returns error while shutting down all sequence components | ESW-346" in {
      val mockSeqCompImpl = mock[SequenceComponentImpl]
      val seqCompUtil: SequenceComponentUtil = new SequenceComponentUtil(locationServiceUtil, sequenceComponentAllocator) {
        override private[sm] def createSequenceComponentImpl(sequenceComponentLocation: AkkaLocation): SequenceComponentImpl =
          mockSeqCompImpl
      }
      when(locationServiceUtil.listAkkaLocationsBy(SequenceComponent))
        .thenReturn(Future.successful(Left(RegistrationListingFailed("error"))))

      seqCompUtil.shutdown(AllSequenceComponents).futureValue should ===(LocationServiceError("error"))

      verify(locationServiceUtil).listAkkaLocationsBy(SequenceComponent)
      verify(mockSeqCompImpl, never).shutdown()
    }
  }

  "mapSequencersToSeqComps" must {
    "return map of sequencer subsystems to sequence component locations | ESW-178" in {
      val sequencers   = Sequencers(ESW, TCS, WFOS)
      val eswPrimary   = sequenceComponentLocation("esw.primary")
      val eswSecondary = sequenceComponentLocation("esw.secondary")
      val tcsPrimary   = sequenceComponentLocation("tcs.primary")
      val wfosPrimary  = sequenceComponentLocation("wfos.primary")

      val seqComps     = List(eswPrimary, tcsPrimary, wfosPrimary, eswSecondary)
      val idleSeqComps = List(eswPrimary, wfosPrimary, eswSecondary)

      val sequenceToSeqCompMapping: Map[Subsystem, AkkaLocation] =
        Map(ESW -> eswPrimary, TCS -> eswSecondary, WFOS -> wfosPrimary)

      when(locationServiceUtil.listAkkaLocationsBy(argEq(SequenceComponent), any[AkkaLocation => Boolean]))
        .thenReturn(Future.successful(Right(seqComps)))
      when(sequenceComponentAllocator.allocate(idleSeqComps, sequencers)).thenReturn(Right(sequenceToSeqCompMapping))

      val sequenceComponentUtil = new SequenceComponentUtil(locationServiceUtil, sequenceComponentAllocator) {
        override private[sm] def idleSequenceComponent(
            sequenceComponentLocation: AkkaLocation
        ): Future[Option[AkkaLocation]] = {
          sequenceComponentLocation.prefix.subsystem match {
            case TCS => Future.successful(None)
            case _   => Future.successful(Some(sequenceComponentLocation))
          }
        }
      }

      val sequencerToSeqCompMap: SequencerToSequenceComponentMap =
        sequenceComponentUtil.mapSequencersToSeqComps(sequencers).rightValue

      sequencerToSeqCompMap should ===(sequenceToSeqCompMapping)
      verify(sequenceComponentAllocator).allocate(idleSeqComps, sequencers)
    }

    "return SequenceComponentNotAvailable if adequate idle sequence components available are not available for sequencer subsystems | ESW-178" in {
      val sequencers = Sequencers(List(ESW, TCS, WFOS))
      val seqComps   = List.empty[AkkaLocation]

      when(locationServiceUtil.listAkkaLocationsBy(argEq(SequenceComponent), any[AkkaLocation => Boolean]))
        .thenReturn(Future.successful(Right(seqComps)))
      when(sequenceComponentAllocator.allocate(seqComps, sequencers))
        .thenReturn(Left(SequenceComponentNotAvailable(sequencers.subsystems)))

      val response: ConfigureResponse.Failure =
        sequenceComponentUtil.mapSequencersToSeqComps(sequencers).leftValue

      response should ===(SequenceComponentNotAvailable(sequencers.subsystems))
    }

    "return LocationServiceError if location service returns error | ESW-178" in {
      val sequencers                = Sequencers(List(ESW, TCS, WFOS))
      val registrationListingFailed = RegistrationListingFailed("error")
      when(locationServiceUtil.listAkkaLocationsBy(argEq(SequenceComponent), any[AkkaLocation => Boolean]))
        .thenReturn(Future.successful(Left(registrationListingFailed)))

      val sequenceComponents = sequenceComponentUtil.mapSequencersToSeqComps(sequencers)

      sequenceComponents.leftValue should ===(LocationServiceError("error"))
    }
  }

  "loadScript" must {
    val darkNight = ObsMode("DarkNight")
    val sequenceComponentUtil: SequenceComponentUtil =
      new SequenceComponentUtil(locationServiceUtil, sequenceComponentAllocator) {
        override private[sm] def createSequenceComponentImpl(sequenceComponentLocation: AkkaLocation) = sequenceComponentApi
      }

    val loadScriptResponses = Table(
      ("seqCompApiResponse", "loadScriptResponse"),
      (
        SequencerLocation(akkaLocation(ComponentId(Prefix(ESW, "DarkNight"), Sequencer))),
        Right(StartSequencerResponse.Started(ComponentId(Prefix(ESW, "DarkNight"), Sequencer)))
      ),
      (ScriptError.LocationServiceError("error"), Left(LocationServiceError("error"))),
      (ScriptError.LoadingScriptFailed("error"), Left(LoadScriptError("error"))),
      (Unhandled(Running, "errorMsg", "error"), Left(LoadScriptError("error")))
    )

    forAll(loadScriptResponses) { (seqCompApiResponse, loadScriptResponse) =>
      s"return ${loadScriptResponse.getClass.getSimpleName} when seqCompApi returns ${seqCompApiResponse.getClass.getSimpleName} | ESW-162" in {
        when(sequenceComponentApi.loadScript(ESW, darkNight)).thenReturn(Future.successful(seqCompApiResponse))

        val eventualResponse: Future[Either[StartSequencerResponse.Failure, StartSequencerResponse.Started]] =
          sequenceComponentUtil.loadScript(ESW, darkNight, sequenceComponentLocation("esw.primary"))

        eventualResponse.futureValue should ===(loadScriptResponse)
      }
    }
  }

  "loadScript with subsystem and obs mode" must {
    val darkNight    = ObsMode("DarkNight")
    val tcsSeqComp   = akkaLocation(ComponentId(Prefix(TCS, "primary"), Sequencer))
    val eswSeqComp   = akkaLocation(ComponentId(Prefix(ESW, "primary"), Sequencer))
    val tcsSequencer = ComponentId(Prefix(TCS, darkNight.name), Sequencer)
    val sequencers   = Sequencers(TCS)

    "return success when script is loaded successfully with subsystem seq comp available | ESW-176" in {
      val seqComps = List(tcsSeqComp, eswSeqComp)

      val sequenceComponentUtil: SequenceComponentUtil =
        new SequenceComponentUtil(locationServiceUtil, sequenceComponentAllocator) {
          override private[sm] def createSequenceComponentImpl(sequenceComponentLocation: AkkaLocation) = sequenceComponentApi
          override private[sm] def idleSequenceComponent(sequenceComponentLocation: AkkaLocation): Future[Option[AkkaLocation]] =
            Future.successful(Some(sequenceComponentLocation))
        }

      when(locationServiceUtil.listAkkaLocationsBy(argEq(SequenceComponent), any[AkkaLocation => Boolean]))
        .thenReturn(Future.successful(Right(List(tcsSeqComp, eswSeqComp))))
      when(sequenceComponentAllocator.allocate(seqComps, sequencers))
        .thenReturn(Right(Map(TCS -> tcsSeqComp)))
      when(sequenceComponentApi.loadScript(TCS, darkNight))
        .thenReturn(Future.successful(SequencerLocation(akkaLocation(tcsSequencer))))

      sequenceComponentUtil.loadScript(TCS, darkNight).futureValue should ===(Started(tcsSequencer))

      verify(sequenceComponentAllocator).allocate(seqComps, sequencers)
      verify(sequenceComponentApi).loadScript(TCS, darkNight)
    }

    "return success when script is loaded successfully with ESW seq comp as fallback | ESW-176" in {
      val idleSeqComps = List(eswSeqComp)

      val sequenceComponentUtil: SequenceComponentUtil =
        new SequenceComponentUtil(locationServiceUtil, sequenceComponentAllocator) {
          override private[sm] def createSequenceComponentImpl(sequenceComponentLocation: AkkaLocation) = sequenceComponentApi
          override private[sm] def idleSequenceComponent(sequenceComponentLocation: AkkaLocation): Future[Option[AkkaLocation]] =
            sequenceComponentLocation.prefix.subsystem match {
              case TCS => Future.successful(None) // mimic that TCS seq comp is not idle
              case _   => Future.successful(Some(sequenceComponentLocation))
            }
        }

      when(locationServiceUtil.listAkkaLocationsBy(argEq(SequenceComponent), any[AkkaLocation => Boolean]))
        .thenReturn(Future.successful(Right(List(tcsSeqComp, eswSeqComp))))
      when(sequenceComponentAllocator.allocate(idleSeqComps, sequencers)).thenReturn(Right(Map(TCS -> eswSeqComp)))
      when(sequenceComponentApi.loadScript(TCS, darkNight))
        .thenReturn(Future.successful(SequencerLocation(akkaLocation(tcsSequencer))))

      sequenceComponentUtil.loadScript(TCS, darkNight).futureValue should ===(Started(tcsSequencer))

      verify(sequenceComponentAllocator).allocate(idleSeqComps, sequencers)
      verify(sequenceComponentApi).loadScript(TCS, darkNight)
    }

    val loadScriptResponses = Table(
      ("seqCompApiResponse", "loadScriptResponse"),
      (ScriptError.LocationServiceError("error"), LocationServiceError("error")),
      (ScriptError.LoadingScriptFailed("error"), LoadScriptError("error")),
      (Unhandled(Running, "errorMsg", "error"), LoadScriptError("error"))
    )

    forAll(loadScriptResponses) { (seqCompApiResponse, loadScriptResponse) =>
      s"return ${loadScriptResponse.getClass.getSimpleName} when seqCompApi returns ${seqCompApiResponse.getClass.getSimpleName} | ESW-176" in {
        val tcsSeqComp = akkaLocation(ComponentId(Prefix(TCS, "primary"), Sequencer))
        val seqComps   = List(tcsSeqComp)
        val sequencers = Sequencers(TCS)

        val sequenceComponentUtil: SequenceComponentUtil =
          new SequenceComponentUtil(locationServiceUtil, sequenceComponentAllocator) {
            override private[sm] def createSequenceComponentImpl(sequenceComponentLocation: AkkaLocation) = sequenceComponentApi
            override private[sm] def idleSequenceComponent(
                sequenceComponentLocation: AkkaLocation
            ): Future[Option[AkkaLocation]] =
              Future.successful(Some(sequenceComponentLocation))
          }

        when(locationServiceUtil.listAkkaLocationsBy(argEq(SequenceComponent), any[AkkaLocation => Boolean]))
          .thenReturn(Future.successful(Right(List(tcsSeqComp))))
        when(sequenceComponentAllocator.allocate(seqComps, sequencers))
          .thenReturn(Right(Map(TCS -> tcsSeqComp)))
        when(sequenceComponentApi.loadScript(TCS, darkNight))
          .thenReturn(Future.successful(seqCompApiResponse))

        val eventualResponse: Future[StartSequencerResponse] =
          sequenceComponentUtil.loadScript(TCS, darkNight)

        eventualResponse.futureValue should ===(loadScriptResponse)
      }
    }
  }

  "unloadScript" must {
    val mockSeqCompApi = mock[SequenceComponentApi]

    val sequenceComponentUtil = new SequenceComponentUtil(locationServiceUtil, sequenceComponentAllocator) {
      override private[sm] def createSequenceComponentImpl(sequenceComponentLocation: AkkaLocation): SequenceComponentApi =
        mockSeqCompApi
    }

    "return Ok if unload script is successful | ESW-166" in {
      val seqCompLocation = sequenceComponentLocation("esw.primary")
      when(mockSeqCompApi.unloadScript()).thenReturn(Future.successful(Ok))

      sequenceComponentUtil.unloadScript(seqCompLocation).futureValue should ===(Ok)

      verify(mockSeqCompApi).unloadScript()
    }
  }

  "restartScript" must {
    val restartScriptResponses = Table(
      "Restart Script Response",
      LoadingScriptFailed("error"),
      ScriptError.LocationServiceError("error"),
      SequencerLocation(sequenceComponentLocation("esw.DarkNight")),
      Unhandled(Running, "RestartScript")
    )

    forAll(restartScriptResponses) { response =>
      s"return appropriate response when ${response.getClass.getSimpleName} | ESW-327" in {
        val mockSeqCompApi = mock[SequenceComponentApi]
        val sequenceComponentUtil = new SequenceComponentUtil(locationServiceUtil, sequenceComponentAllocator) {
          override private[sm] def createSequenceComponentImpl(sequenceComponentLocation: AkkaLocation): SequenceComponentApi =
            mockSeqCompApi
        }

        val seqCompLocation = sequenceComponentLocation("esw.primary")
        when(mockSeqCompApi.restartScript()).thenReturn(Future.successful(response))

        sequenceComponentUtil.restartScript(seqCompLocation).futureValue should ===(response)

        verify(mockSeqCompApi).restartScript()
      }
    }
  }

  "getSequenceComponentStatus" must {
    "return mapping of Sequence component to sequencer script running  | ESW-349" in {
      val eswSeqComp = mock[SequenceComponentImpl]

      val seqCompUtil: SequenceComponentUtil = new SequenceComponentUtil(locationServiceUtil, sequenceComponentAllocator) {
        override private[sm] def createSequenceComponentImpl(sequenceComponentLocation: AkkaLocation): SequenceComponentImpl =
          sequenceComponentLocation.prefix.subsystem match {
            case ESW => eswSeqComp
            case _   => mock[SequenceComponentImpl]
          }
      }

      val eswSeqCompId = ComponentId(Prefix(ESW, "primary"), SequenceComponent)
      val tcsSeqCompId = ComponentId(Prefix(TCS, "primary"), SequenceComponent)

      when(locationServiceUtil.find(AkkaConnection(eswSeqCompId)))
        .thenReturn(Future.successful(Right(akkaLocation(eswSeqCompId))))
      when(locationServiceUtil.find(AkkaConnection(tcsSeqCompId)))
        .thenReturn(Future.successful(Left(RegistrationListingFailed("error"))))

      val sequencerLocation = Some(akkaLocation(ComponentId(Prefix(ESW, "darknight"), Sequencer)))

      when(eswSeqComp.status)
        .thenReturn(Future.successful(GetStatusResponse(sequencerLocation)))

      val expectedResponse = Map(eswSeqCompId -> sequencerLocation)

      seqCompUtil.getSequenceComponentStatus(List(eswSeqCompId, tcsSeqCompId)).futureValue should ===(expectedResponse)

      verify(locationServiceUtil).find(AkkaConnection(eswSeqCompId))
      verify(locationServiceUtil).find(AkkaConnection(tcsSeqCompId))
      verify(eswSeqComp).status
    }

    "return mapping for empty list of sequence components | ESW-349" in {
      val seqCompUtil: SequenceComponentUtil = new SequenceComponentUtil(locationServiceUtil, sequenceComponentAllocator) {
        override private[sm] def createSequenceComponentImpl(sequenceComponentLocation: AkkaLocation): SequenceComponentImpl =
          mock[SequenceComponentImpl]
      }

      val expectedResponse = Map.empty[ComponentId, Option[AkkaLocation]]

      seqCompUtil.getSequenceComponentStatus(List.empty[ComponentId]).futureValue should ===(expectedResponse)
    }
  }

  private def akkaLocation(componentId: ComponentId): AkkaLocation = AkkaLocation(AkkaConnection(componentId), URI.create(""))
  private def sequenceComponentLocation(prefixStr: String)         = akkaLocation(ComponentId(Prefix(prefixStr), SequenceComponent))
}
