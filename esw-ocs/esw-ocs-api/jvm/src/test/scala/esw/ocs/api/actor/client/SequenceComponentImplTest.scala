package esw.ocs.api.actor.client

import java.net.URI

import akka.Done
import akka.actor.typed.{ActorSystem, SpawnProtocol}
import akka.actor.typed.scaladsl.Behaviors
import akka.util.Timeout
import csw.location.api.extensions.ActorExtension.RichActor
import csw.location.api.models.ComponentType.SequenceComponent
import csw.location.api.models.Connection.AkkaConnection
import csw.location.api.models.{AkkaLocation, ComponentId, ComponentType}
import csw.prefix.models.Subsystem.ESW
import csw.prefix.models.{Prefix, Subsystem}
import esw.ocs.api.actor.messages.SequenceComponentMsg
import esw.ocs.api.actor.messages.SequenceComponentMsg._
import esw.ocs.api.protocol.{GetStatusResponse, ScriptError, ScriptResponse}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import scala.concurrent.duration.DurationInt
import scala.concurrent.ExecutionContext

class SequenceComponentImplTest extends AnyWordSpecLike with Matchers with ScalaFutures {
  private implicit val system           = ActorSystem(SpawnProtocol(), "SequenceComponentImplTest")
  private implicit val timeout: Timeout = 10.seconds

  private val location =
    AkkaLocation(AkkaConnection(ComponentId(Prefix("esw.test"), ComponentType.Sequencer)), new URI("uri"))
  private val loadScriptResponse    = ScriptResponse(Right(location))
  private val restartResponse       = ScriptResponse(Left(ScriptError("Restart error")))
  private val getStatusResponse     = GetStatusResponse(Some(location))
  implicit val ec: ExecutionContext = system.executionContext

  private val mockedBehavior: Behaviors.Receive[SequenceComponentMsg] = Behaviors.receiveMessage[SequenceComponentMsg] { msg =>
    msg match {
      case LoadScript(_, _, replyTo) => replyTo ! loadScriptResponse
      case GetStatus(replyTo)        => replyTo ! getStatusResponse
      case UnloadScript(replyTo)     => replyTo ! Done
      case Restart(replyTo)          => replyTo ! restartResponse
      case Stop                      => Behaviors.stopped
    }
    Behaviors.same
  }

  private val sequenceComponent = system.systemActorOf(mockedBehavior, "sequence_component")
  private val sequenceComponentLocation = AkkaLocation(
    AkkaConnection(ComponentId(Prefix(ESW, "primary"), SequenceComponent)),
    sequenceComponent.toURI
  )

  private val sequenceComponentClient = new SequenceComponentImpl(sequenceComponentLocation)

  "LoadScript | ESW-103" in {
    sequenceComponentClient.loadScript(Subsystem.ESW, "darknight").futureValue should ===(loadScriptResponse)
  }

  "Restart | ESW-141" in {
    sequenceComponentClient.restart().futureValue should ===(restartResponse)
  }

  "GetStatus | ESW-103" in {
    sequenceComponentClient.status.futureValue should ===(getStatusResponse)
  }

  "UnloadScript | ESW-103" in {
    sequenceComponentClient.unloadScript().futureValue should ===(Done)
  }
}
