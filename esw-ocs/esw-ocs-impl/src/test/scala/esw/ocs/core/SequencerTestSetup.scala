package esw.ocs.core

import akka.Done
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.util.Timeout
import csw.command.client.CommandResponseManager
import csw.command.client.messages.sequencer.{LoadAndStartSequence, SequencerMsg}
import csw.location.api.scaladsl.LocationService
import csw.location.models.ComponentId
import csw.params.commands.CommandResponse.{Completed, SubmitResponse}
import csw.params.commands.{Sequence, SequenceCommand}
import csw.params.core.models.Id
import esw.ocs.api.models.SequencerState
import esw.ocs.api.models.SequencerState.{Idle, InProgress}
import esw.ocs.api.models.messages.SequencerMessages.{Pause, _}
import esw.ocs.api.models.messages.{LoadSequenceResponse, _}
import esw.ocs.dsl.Script
import org.mockito.Mockito.when
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.{Assertion, Matchers}
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.duration.DurationLong
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.Success

class SequencerTestSetup(sequence: Sequence)(implicit system: ActorSystem[_], timeout: Timeout) {
  import Matchers._
  import MockitoSugar._

  implicit val ec: ExecutionContext = system.executionContext

  private val componentId                 = mock[ComponentId]
  private val script                      = mock[Script]
  private val locationService             = mock[LocationService]
  private val crm: CommandResponseManager = mock[CommandResponseManager]
  private val sequencerBehavior           = new SequencerBehavior(componentId, script, locationService, crm)

  val sequencerActor: ActorRef[SequencerMsg] =
    Await.result(system.systemActorOf(sequencerBehavior.setup, s"SequencerActor${math.random()}"), 5.seconds)

  private val completionPromise = Promise[SubmitResponse]()
  mockCommand(sequence.runId, completionPromise.future)

  def mockCommand(id: Id, mockResponse: Future[SubmitResponse]): Unit = {
    when(crm.queryFinal(id)).thenAnswer(_ => mockResponse)
  }

  // mock all commands to return Completed submit response
  def mockAllCommands(): Unit =
    sequence.commands.foreach { command =>
      mockCommand(command.runId, Future.successful(Completed(command.runId)))
    }

  def loadSequenceAndAssertResponse(expected: LoadSequenceResponse): Unit = {
    val probe = TestProbe[LoadSequenceResponse]
    sequencerActor ! LoadSequence(sequence, probe.ref)
    probe.expectMessage(expected)
  }

  def loadAndStartSequenceThenAssertInProgress(): Assertion = {
    val probe = TestProbe[SubmitResponse]
    sequencerActor ! LoadAndStartSequence(sequence, probe.ref)

    val p: TestProbe[StepListResponse] = TestProbe[StepListResponse]
    eventually {
      sequencerActor ! GetSequence(p.ref)
      val result = p.expectMessageType[StepListResult]
      result.stepList.isDefined shouldBe true
    }
  }

  def pullAllStepsAndAssertSequenceIsFinished(): Assertion = {
    eventually {
      val probe = TestProbe[StepListResponse]
      sequencerActor ! GetSequence(probe.ref)
      val result = probe.expectMessageType[StepListResult]
      result.stepList.get.runId should ===(sequence.runId)
    }

    pullAllSteps()
    eventually(assertSequenceIsFinished())
  }

  def assertCurrentSequence(expected: StepListResponse): Unit = assertStepListResponse(expected, GetSequence)

  def assertPreviousSequence(expected: StepListResponse): Unit = assertStepListResponse(expected, GetPreviousSequence)

  def abortSequenceAndAssertResponse(
      response: OkOrUnhandledResponse,
      expectedState: SequencerState[SequencerMsg]
  ): TestProbe[OkOrUnhandledResponse] = {
    val probe                          = TestProbe[OkOrUnhandledResponse]
    val p: TestProbe[StepListResponse] = TestProbe[StepListResponse]

    when(script.executeAbort()).thenReturn(Future.successful(Done))
    sequencerActor ! AbortSequence(probe.ref)

    //GetSequence msg while aborting sequence
    sequencerActor ! GetSequence(p.ref)

    probe.expectMessage(response)

    //GetSequence should be handled and return response while aborting sequence
    p.expectMessageType[StepListResult]

    //After abort sequence
    sequencerActor ! GetSequence(p.ref)
    val result = p.expectMessageType[StepListResult]
    expectedState match {
      case Idle                            => result.stepList shouldBe None
      case InProgress                      => result.stepList.get.nextPending shouldBe None
      case x: SequencerState[SequencerMsg] => assert(false, s"$x is not valid state after AbortSequence")
    }
    probe
  }

  def pauseAndAssertResponse(response: PauseResponse): PauseResponse = {
    val probe = TestProbe[PauseResponse]
    sequencerActor ! Pause(probe.ref)
    probe.expectMessage(response)
  }

  def resumeAndAssertResponse(response: OkOrUnhandledResponse): OkOrUnhandledResponse = {
    val probe = TestProbe[OkOrUnhandledResponse]
    sequencerActor ! Resume(probe.ref)
    probe.expectMessage(response)
  }

  def replaceAndAssertResponse(idToReplace: Id, commands: List[SequenceCommand], response: GenericResponse): GenericResponse = {
    val replaceResProbe = TestProbe[GenericResponse]()
    sequencerActor ! Replace(idToReplace, commands, replaceResProbe.ref)
    replaceResProbe.expectMessage(response)
  }

  def assertUnhandled[T >: Unhandled <: EswSequencerResponse](
      state: SequencerState[SequencerMsg],
      msg: ActorRef[T] => EswSequencerMessage
  ): Unit = {
    val probe            = TestProbe[T]
    val sequencerMessage = msg(probe.ref)
    sequencerActor ! sequencerMessage
    probe.expectMessage(Unhandled(state, sequencerMessage.getClass.getSimpleName))
  }

  def assertUnhandled[T >: Unhandled <: EswSequencerResponse](
      state: SequencerState[SequencerMsg],
      msgs: (ActorRef[T] => EswSequencerMessage)*
  ): Unit =
    msgs.foreach(assertUnhandled(state, _))

  private def assertSequenceIsFinished(): Assertion = {
    val probe = TestProbe[StepListResponse]
    sequencerActor ! GetSequence(probe.ref)
    val result   = probe.expectMessageType[StepListResult]
    val finished = result.stepList.get.isFinished

    if (finished) completionPromise.complete(Success(Completed(sequence.runId)))

    finished should ===(true)
  }

  def pullNextCommand(): PullNextResult = {
    val probe = TestProbe[PullNextResponse]
    sequencerActor ! PullNext(probe.ref)
    probe.expectMessageType[PullNextResult]
  }

  // this is to simulate engine pull and executing steps
  private def pullAllSteps(): Seq[PullNextResult] =
    (1 to sequence.commands.size).map(_ => pullNextCommand())

  def getCurrentSequence(): StepListResult = {
    val probe = TestProbe[StepListResponse]
    sequencerActor ! GetSequence(probe.ref)
    probe.expectMessageType[StepListResult]
  }

  private def assertStepListResponse(expected: StepListResponse, msg: ActorRef[StepListResponse] => SequencerMsg): Unit = {
    val probe = TestProbe[StepListResponse]
    sequencerActor ! msg(probe.ref)
    probe.expectMessage(expected)
  }
}

object SequencerTestSetup {

  def idle(
      sequence: Sequence,
      mockCommands: Boolean = true
  )(implicit system: ActorSystem[_], timeout: Timeout): SequencerTestSetup = {
    val testSetup = new SequencerTestSetup(sequence)
    if (mockCommands) testSetup.mockAllCommands()
    testSetup
  }

  def loaded(
      sequence: Sequence,
      mockCommands: Boolean = true
  )(implicit system: ActorSystem[_], timeout: Timeout): SequencerTestSetup = {
    val sequencerSetup = idle(sequence)
    sequencerSetup.loadSequenceAndAssertResponse(Ok)
    sequencerSetup
  }

  def inProgress(
      sequence: Sequence,
      mockCommands: Boolean = true
  )(implicit system: ActorSystem[_], timeout: Timeout): SequencerTestSetup = {
    val sequencerSetup = idle(sequence, mockCommands)
    sequencerSetup.loadAndStartSequenceThenAssertInProgress()
    sequencerSetup
  }

  def finished(
      sequence: Sequence,
      mockCommands: Boolean = true
  )(implicit system: ActorSystem[_], timeout: Timeout): SequencerTestSetup = {
    val sequencerSetup = new SequencerTestSetup(sequence)
    import sequencerSetup._
    if (mockCommands) mockAllCommands()
    val probe = TestProbe[SubmitResponse]
    sequencerActor ! LoadAndStartSequence(sequence, probe.ref)
    pullAllStepsAndAssertSequenceIsFinished()
    probe.expectMessage(Completed(sequence.runId))
    sequencerSetup
  }
}
