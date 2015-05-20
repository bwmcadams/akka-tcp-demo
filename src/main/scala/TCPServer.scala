import akka.actor._
import akka.io.{ IO, Tcp }
import akka.util.ByteString
import java.net.InetSocketAddress
import akka.io.{ IO, Tcp }

object TCPServerApp extends App {
  val system = ActorSystem()
  system.actorOf(Props[TCPServer])
}

class TCPServer extends Actor with ActorLogging {

  import Tcp._
  import context.system
  
  val TCPPort = 4200

  IO(Tcp) ! Bind(self, new InetSocketAddress(TCPPort))

  def receive = {
    case b @ Bound(addr) =>
      log.info("Bound To Port '{}' on address '{}'", TCPPort, addr)

    case CommandFailed(_: Bind) =>
      log.error("Binding Command Failed. Exiting.")
      context stop self

    case c @ Connected(remote, local) =>
      log.info("Client Connected. Remote: {} Local: {}", remote, local)
      val handler = context.actorOf(Props[TCPHandler])
      val connection = sender()
      connection ! Register(handler)
  }

}

class TCPHandler extends Actor with ActorLogging {
  import Tcp._

  def receive = {
    case Received(data) =>
      // For now, echo back to the client
      sender() ! Write(data)
    case PeerClosed =>
      context stop self
  }
}
