package esw.ocs.script.server

import org.apache.pekko.actor.typed.{ActorSystem, SpawnProtocol}
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.server.Route

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class OcsScriptServer(routes: Route)(implicit
    typedSystem: ActorSystem[SpawnProtocol.Command],
    ec: ExecutionContext
) {
  def start(): Future[Http.ServerBinding] = {
    val f = Http().newServerAt("0.0.0.0", 0).bind(routes)
    f.onComplete {
      case Success(b) =>
        println(s"Script server online at http://localhost:${b.localAddress.getPort}")
      case Failure(ex) =>
        ex.printStackTrace()
    }
    f
  }
}
