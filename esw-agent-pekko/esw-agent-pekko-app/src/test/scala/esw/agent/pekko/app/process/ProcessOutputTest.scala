package esw.agent.pekko.app.process

import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import esw.agent.pekko.app.process.ProcessOutput.ConsoleWriter
import esw.testcommons.BaseTestSuite
import org.scalatest.time.{Millis, Seconds, Span}

class ProcessOutputTest extends BaseTestSuite {

  private class FakeConsoleWriter extends ConsoleWriter {
    val data: AtomicReference[List[(String, Boolean)]] = new AtomicReference[List[(String, Boolean)]](List.empty)

    override def write(value: String): Unit    = data.getAndUpdate(_.appended((value, false)))
    override def writeErr(value: String): Unit = data.getAndUpdate(_.appended((value, true)))
  }

  private implicit val actorSystem: ActorSystem[?] = ActorSystem(Behaviors.empty, "test")

  override def afterAll(): Unit = {
    super.afterAll()
    actorSystem.terminate()
  }

  "attachToProcess" must {
    "attach the output of given process with output of current process | ESW-237" in {
      val fakeConsoleWriter = new FakeConsoleWriter()
      val processOutput     = new ProcessOutput(fakeConsoleWriter)
      val processName       = "testprocess"
      val process           = runCommand
      processOutput.attachToProcess(process, processName)

      implicit val patienceConfig: PatienceConfig = PatienceConfig(Span(3, Seconds), Span(500, Millis))

      eventually {
        fakeConsoleWriter.data.get should contain allElementsOf
        Seq(
          (s"[$processName] stdout text", false),
          (s"[$processName] stderr text", true)
        )
      }
    }
  }

  private def runCommand: Process = {
    val filePath = getClass.getResource("/test-executable.sh").getPath
    val args     = List(filePath)
    val processBuilder = new ProcessBuilder(args*)
      .redirectInput(ProcessBuilder.Redirect.INHERIT)

    processBuilder.start()
  }
}
