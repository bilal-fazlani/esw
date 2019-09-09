package esw.ocs.dsl

import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch

import csw.params.commands.{CommandName, Observe, Setup}
import csw.params.core.models.Prefix
import esw.ocs.api.BaseTestSuite
import esw.ocs.macros.StrandEc

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.{DurationDouble, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

class ScriptDslTest extends BaseTestSuite {
  override implicit def patienceConfig: PatienceConfig = PatienceConfig(20.seconds)
  "ScriptDsl" must {
    "allow adding and executing setup handler" in {
      var receivedPrefix: Option[Prefix] = None

      val script: ScriptDsl = new ScriptDsl {
        override def csw: CswServices             = ???
        override val loopInterval: FiniteDuration = 100.millis

        handleSetupCommand("iris") { cmd =>
          spawn {
            receivedPrefix = Some(cmd.source)
            ()
          }
        }
      }
      val prefix    = Prefix("iris.move")
      val irisSetup = Setup(prefix, CommandName("iris"), None)
      script.execute(irisSetup).futureValue

      receivedPrefix.value shouldBe prefix
    }

    "allow adding and executing observe handler" in {
      var receivedPrefix: Option[Prefix] = None

      val script: ScriptDsl = new ScriptDsl {
        override def csw: CswServices             = ???
        override val loopInterval: FiniteDuration = 100.millis
        handleObserveCommand("iris") { cmd =>
          spawn {
            receivedPrefix = Some(cmd.source)
            ()
          }
        }
      }
      val prefix      = Prefix("iris.move")
      val irisObserve = Observe(prefix, CommandName("iris"), None)
      script.execute(irisObserve).futureValue

      receivedPrefix.value shouldBe prefix
    }

    "allow adding and executing multiple shutdown handlers in order" in {
      val orderOfShutdownCalled = ArrayBuffer.empty[Int]

      val script: ScriptDsl = new ScriptDsl {
        override def csw: CswServices             = ???
        override val loopInterval: FiniteDuration = 100.millis
        handleShutdown {
          spawn {
            orderOfShutdownCalled += 1
          }
        }

        handleShutdown {
          spawn {
            orderOfShutdownCalled += 2
          }
        }
      }

      script.executeShutdown().futureValue
      orderOfShutdownCalled shouldBe ArrayBuffer(1, 2)
    }

    "allow adding and executing multiple abort handlers in order" in {
      val orderOfAbortCalled = ArrayBuffer.empty[Int]

      val script: ScriptDsl = new ScriptDsl {
        override def csw: CswServices             = ???
        override val loopInterval: FiniteDuration = 100.millis
        handleAbort {
          spawn {
            orderOfAbortCalled += 1
          }
        }

        handleAbort {
          spawn {
            orderOfAbortCalled += 2
          }
        }
      }

      script.executeAbort().futureValue
      orderOfAbortCalled shouldBe ArrayBuffer(1, 2)
    }

    "allow running operations sequentially | ESW-88" in {

      val latch = new CountDownLatch(3)
      val script: ScriptDsl = new ScriptDsl {
        override def csw: CswServices             = ???
        override val loopInterval: FiniteDuration = 1.millis

        def decrement: Future[Unit] = Future { latch.countDown() }(ExecutionContext.global)

        loop(1.seconds) {
          spawn {
            val running = Thread.getAllStackTraces.keySet()
            running.forEach(a => {
              println(
                a.getName + "   " + a.getState + "   "
              )
            })

            stopIf(false)
          }
        }

        handleSetupCommand("iris") { _ =>
          spawn {
            println(s"Handle setup ---------> ${Thread.currentThread().getName}")
            println("blocking")
            Thread.sleep(20000000)
            // await utility provided in ControlDsl, asynchronously blocks for future to complete
            decrement.await
            decrement.await
            decrement.await
            println("Handle setup ---------> Finished")
          }
        }
      }

      val prefix    = Prefix("iris.move")
      val irisSetup = Setup(prefix, CommandName("iris"), None)
      script.execute(irisSetup).futureValue
      println(s"^^^^^^^^^^^^^^^^^^^ ${Thread.currentThread().getName}")
      Thread.sleep(1000000000)

//      latch.getCount should ===(0L)
    }
  }

}
