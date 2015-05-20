import java.net.InetSocketAddress

import akka.actor.{ActorLogging, Actor, Props, ActorSystem}
import akka.io.{IO, Tcp}
import akka.routing.{RoundRobinRoutingLogic, Router, ActorRefRoutee}
import akka.util.ByteString
import scala.concurrent.duration._
import scala.language.postfixOps

object TCPClientApp extends App {
  val host = args(0)
  val port = Integer.parseInt(args(1))
  val numClients = Integer.parseInt(args(2))
  val system = ActorSystem()
  system.actorOf(Props(classOf[TCPClientMaster], new InetSocketAddress(host, port), numClients))
}

class TCPClientMaster(remoteAddr: InetSocketAddress, numClients: Int) extends Actor with ActorLogging {
  var router = {
    val routees = Vector.fill(numClients) {
      val r = context.actorOf(Props(classOf[TCPClient], remoteAddr, numClients))
      context watch r
      ActorRefRoutee(r)
    }
    Router(RoundRobinRoutingLogic(), routees)
  }
  def receive = {
    case anything =>
      log.info("Got a work request, we don't handle anything.")
  }
}

class TCPClient(remoteAddr: InetSocketAddress, numClients: Int) extends Actor with ActorLogging {
  import Tcp._
  import context.system
  import context.dispatcher


  IO(Tcp) ! Connect(remoteAddr)

  def receive = {
    case CommandFailed(_: Connect) =>
      log.warning("connect failed")
      context stop self

    case c @ Connected(remote, local) =>
      log.info("Connected Remote: '{}' Local: '{}'", remote, local)
      val connection = sender()
      connection ! Register(self)
      context become {
        case data: ByteString =>
          connection ! Write(data)
        case Tick =>
          connection ! Write(ByteString("Mr. Watson--come here--I want to see you."))
        case CommandFailed(w: Write) =>
          // O/S buffer was full
          log.warning("write failed")
        case Received(data) =>
          log.info("Received data: '{}'", data)
        case "close" =>
          connection ! Close
        case _: ConnectionClosed =>
          // TODO - Reconnect here
          log.warning("Connection closed")

          // quiesce for a minute
          // TODO - Scheduler to reconnect in 1 minute
          // roll back our current behavior
          context.unbecome()
      }
      // schedule occasional ticks that we'll send echo messages to the remote end
      val cancellable =
        system.scheduler.schedule(0 milliseconds,
          15 seconds,
          self,
          Tick)
  }
}

case object Tick
