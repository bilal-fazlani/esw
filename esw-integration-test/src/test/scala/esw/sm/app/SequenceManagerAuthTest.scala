package esw.sm.app

import csw.location.api.models.ComponentId
import csw.location.api.models.ComponentType.Sequencer
import csw.prefix.models.Prefix
import csw.prefix.models.Subsystem.{AOESW, ESW, IRIS, TCS}
import esw.ocs.api.models.ObsMode
import esw.ocs.testkit.EswTestKit
import esw.ocs.testkit.Service.AAS
import esw.sm.api.SequenceManagerApi
import esw.sm.api.models.ProvisionConfig
import esw.sm.api.protocol._
import msocket.impl.HttpError
import org.scalatest.prop.Tables.Table

import scala.concurrent.{Await, Future}

class SequenceManagerAuthTest extends EswTestKit(AAS) {

  private val smPrefix        = Prefix(ESW, "sequence_manager")
  private val IRIS_CAL        = ObsMode("IRIS_Cal")
  private val IRIS_Darknight  = ObsMode("IRIS_Darknight")
  private val WFOS_Cal        = ObsMode("WFOS_Cal")
  private val seqCompPrefix   = Prefix(ESW, "primary")
  private val provisionConfig = ProvisionConfig(seqCompPrefix -> 1)

  private val testCases = Table[String, SequenceManagerApi => Future[Any]](
    ("Name", "Command"),
    ("configure", _.configure(IRIS_CAL)),
    ("provision", _.provision(provisionConfig)),
    ("startSequencer", _.startSequencer(ESW, IRIS_CAL)),
    ("restartSequencer", _.restartSequencer(ESW, IRIS_CAL)),
    ("shutdownSequencer", _.shutdownSequencer(ESW, IRIS_CAL)),
    ("shutdownSubsystemSequencers", _.shutdownSubsystemSequencers(ESW)),
    ("shutdownObsModeSequencers", _.shutdownObsModeSequencers(IRIS_CAL)),
    ("shutdownAllSequencers", _.shutdownAllSequencers()),
    ("shutdownSequenceComponent", _.shutdownSequenceComponent(seqCompPrefix)),
    ("shutdownAllSequenceComponents", _.shutdownAllSequenceComponents())
  )

  override def afterEach(): Unit = {
    TestSetup.cleanup()
    super.afterEach()
  }

  "Sequence Manager" must {
    testCases.foreach {
      case (name, action) =>
        s"return 401 when $name request does not have token | ESW-332" in {
          val sequenceManagerApi = TestSetup.startSequenceManagerAuthEnabled(smPrefix, () => None)

          val httpError = intercept[HttpError](Await.result(action(sequenceManagerApi), defaultTimeout))
          println(httpError.message)
          httpError.statusCode shouldBe 401
        }
    }

    testCases.foreach {
      case (name, action) =>
        s"return 403 when $name request does not have ESW_user role | ESW-332" in {

          val sequenceManagerApi = TestSetup.startSequenceManagerAuthEnabled(smPrefix, tokenWithIrisUserRole)

          val httpError = intercept[HttpError](Await.result(action(sequenceManagerApi), defaultTimeout))
          httpError.statusCode shouldBe 403
        }
    }

    "return 200 when configure, clean request has ESW_user role | ESW-332" in {
      val eswSeqCompPrefix   = Prefix(ESW, "primary")
      val irisSeqCompPrefix  = Prefix(IRIS, "primary")
      val aoeswSeqCompPrefix = Prefix(AOESW, "primary")
      val componentId        = ComponentId(Prefix(ESW, IRIS_CAL.name), Sequencer)

      TestSetup.startSequenceComponents(eswSeqCompPrefix, irisSeqCompPrefix, aoeswSeqCompPrefix)

      // sequence manager with ESW-user role token
      val sequenceManagerApi = TestSetup.startSequenceManagerAuthEnabled(smPrefix, tokenWithEswUserRole)

      // configure obs mode
      sequenceManagerApi.configure(IRIS_CAL).futureValue shouldBe ConfigureResponse.Success(componentId)

      // configure obs mode
      sequenceManagerApi.shutdownObsModeSequencers(IRIS_CAL).futureValue shouldBe ShutdownSequencersResponse.Success
    }

    "return 200 when start sequencer, restart sequencer and shutdown sequencer request has ESW_user role | ESW-332" in {
      val eswSeqCompPrefix = Prefix(ESW, "primary")
      TestSetup.startSequenceComponents(eswSeqCompPrefix)

      val sequenceManagerApi = TestSetup.startSequenceManagerAuthEnabled(smPrefix, tokenWithEswUserRole)
      val componentId        = ComponentId(Prefix(ESW, WFOS_Cal.name), Sequencer)

      // start sequencer
      sequenceManagerApi.startSequencer(ESW, WFOS_Cal).futureValue shouldBe StartSequencerResponse.Started(componentId)
      // restart sequencer
      sequenceManagerApi.restartSequencer(ESW, WFOS_Cal).futureValue shouldBe RestartSequencerResponse.Success(componentId)
      // shutdown sequencer
      sequenceManagerApi.shutdownSequencer(ESW, WFOS_Cal).futureValue shouldBe ShutdownSequencersResponse.Success
    }

    "return 200 when shutdown all sequencer request has ESW_user role | ESW-332" in {
      val eswSeqCompPrefix  = Prefix(ESW, "primary")
      val irisSeqCompPrefix = Prefix(IRIS, "primary")
      val tcsSeqCompPrefix  = Prefix(TCS, "primary")
      val componentId       = ComponentId(Prefix(ESW, IRIS_Darknight.name), Sequencer)

      TestSetup.startSequenceComponents(eswSeqCompPrefix, irisSeqCompPrefix, tcsSeqCompPrefix)

      val sequenceManagerApi = TestSetup.startSequenceManagerAuthEnabled(smPrefix, tokenWithEswUserRole)

      // configure obs mode
      sequenceManagerApi.configure(IRIS_Darknight).futureValue shouldBe ConfigureResponse.Success(componentId)

      // shutdown all sequencers
      sequenceManagerApi.shutdownAllSequencers().futureValue shouldBe ShutdownSequencersResponse.Success
    }

    "return 200 even when get running obs modes request does not have token | ESW-332" in {
      val sequenceManagerApi = TestSetup.startSequenceManagerAuthEnabled(smPrefix, () => None)
      sequenceManagerApi.getRunningObsModes.futureValue shouldBe GetRunningObsModesResponse.Success(Set.empty)
    }

    "return 200 when shutdown sequence component request has ESW_user role | ESW-332" in {
      val eswSeqCompPrefix = Prefix(ESW, "primary")

      TestSetup.startSequenceComponents(eswSeqCompPrefix)

      val sequenceManagerApi = TestSetup.startSequenceManagerAuthEnabled(smPrefix, tokenWithEswUserRole)

      // shutdown sequence component
      sequenceManagerApi
        .shutdownSequenceComponent(eswSeqCompPrefix)
        .futureValue shouldBe ShutdownSequenceComponentResponse.Success
    }

    "return 200 when shutdown all sequence components request has ESW_user role | ESW-332" in {
      val eswSeqCompPrefix1 = Prefix(ESW, "primary")
      val eswSeqCompPrefix2 = Prefix(ESW, "secondary")

      TestSetup.startSequenceComponents(eswSeqCompPrefix1, eswSeqCompPrefix2)

      val sequenceManagerApi = TestSetup.startSequenceManagerAuthEnabled(smPrefix, tokenWithEswUserRole)

      // shutdown sequence component
      sequenceManagerApi
        .shutdownAllSequenceComponents()
        .futureValue shouldBe ShutdownSequenceComponentResponse.Success
    }
  }
}
