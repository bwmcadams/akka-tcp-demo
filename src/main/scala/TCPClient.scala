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
  var activeConnections = 0
  var receivedResponses = 0

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global
    context.system.scheduler.schedule(0 milliseconds,
      5 seconds,
      self,
      PrintStat)
    super.preStart()
  }

  var router = {
    val routees = Vector.fill(numClients) {
      val r = context.actorOf(Props(classOf[TCPClient], remoteAddr, numClients))
      context watch r
      ActorRefRoutee(r)
    }
    Router(RoundRobinRoutingLogic(), routees)
  }

  def receive = {
    case ClientConnected => activeConnections += 1
    case ClientDisconnected => activeConnections -= 1
    case ReceivedResponse => receivedResponses += 1
    case PrintStat => log.info(s"active connections: ${activeConnections} | received responses: ${receivedResponses}")
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
    case r @ Reconnect =>
      log.info("Reconnect request made. Trying...")
      IO(Tcp) ! Connect(remoteAddr)
    case c @ Connected(remote, local) =>
      //      log.info("Connected Remote: '{}' Local: '{}'", remote, local)
      val connection = sender()
      connection ! Register(self)
      context.parent ! ClientConnected
      context become {
        case data: ByteString =>
          connection ! Write(data)
        case Tick =>
          connection ! Write(ByteString("Mr. Watson--come here--I want to see you."))
        case CommandFailed(w: Write) =>
          // O/S buffer was full
          log.warning("write failed")
        case Received(data) =>
          context.parent ! ReceivedResponse
        case "close" =>
          connection ! Close
        case _: ConnectionClosed =>
          // roll back our current behavior
          context.unbecome()
          context.parent ! ClientDisconnected
          // quiesce for a minute
          system.scheduler.scheduleOnce(1 minute, self, Reconnect)
      }
      // schedule occasional ticks that we'll send echo messages to the remote end
      system.scheduler.schedule(0 milliseconds,
        15 seconds,
        self,
        Tick)
  }
}

case object Tick

case object Reconnect

case object ClientConnected

case object ClientDisconnected

case object ReceivedResponse

case object PrintStat
