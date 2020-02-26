package esw.ocs.impl.blockhound

import java.util.concurrent.Executors

import esw.ocs.api.BaseTestSuite
import reactor.blockhound.BlockHound
import reactor.blockhound.integration.BlockHoundIntegration

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}

class TestScriptEcIntegration(threadName: String, blockingCallback: String => Unit) extends ScriptEcIntegration(threadName) {
  override def applyTo(builder: BlockHound.Builder): Unit = {
    super.applyTo(builder)
    builder.blockingMethodCallback(m => blockingCallback(m.toString))
  }
}

class ScriptEcIntegrationTest extends BaseTestSuite {
  "ScriptEcIntegration" must {
    "detect blocking calls for script thread | ESW-290" in {
      val testScriptThread    = "test-script-thread-1"
      var assertString        = ""
      val blockingCallback    = (msg: String) => { assertString = msg }
      val scriptEcIntegration = new TestScriptEcIntegration(testScriptThread, blockingCallback)
      scriptEcIntegration.isInstanceOf[BlockHoundIntegration] shouldBe (true)

      val testEc: ExecutionContext =
        ExecutionContext.fromExecutorService(Executors.newSingleThreadExecutor((r: Runnable) => new Thread(r, testScriptThread)))
      BlockHoundWiring.addIntegration(scriptEcIntegration)
      BlockHoundWiring.install()

      Await.result(Future { Thread.sleep(1000) }(testEc), 10.seconds)

      assertString shouldBe ("java.lang.Thread.sleep")
    }

    "ignore blocking println calls for script thread | ESW-290" in {
      val testScriptThread    = "test-script-thread-2"
      var assertString        = ""
      val blockingCallback    = (msg: String) => { assertString = msg }
      val scriptEcIntegration = new TestScriptEcIntegration(testScriptThread, blockingCallback)
      scriptEcIntegration.isInstanceOf[BlockHoundIntegration] shouldBe (true)

      val testEc: ExecutionContext =
        ExecutionContext.fromExecutorService(Executors.newSingleThreadExecutor((r: Runnable) => new Thread(r, testScriptThread)))
      BlockHoundWiring.addIntegration(scriptEcIntegration)
      BlockHoundWiring.install()

      Await.result(Future { println("hello world") }(testEc), 10.seconds)

      assertString shouldBe ("")
    }
  }
}