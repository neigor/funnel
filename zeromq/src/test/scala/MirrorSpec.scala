package oncue.svc.funnel
package zeromq

import java.net.URI
import scalaz.concurrent.Task
import scalaz.stream.{Channel,Process,io}
import scalaz.stream.async.signalOf
import org.scalatest.{FlatSpec,Matchers,BeforeAndAfterAll}
import sockets._
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration._

class MirrorSpec extends FlatSpec with Matchers with BeforeAndAfterAll {

  lazy val S  = signalOf[Boolean](true)
  lazy val W  = 30.seconds

  lazy val U1 = new URI("tcp://127.0.0.1:7894")
  lazy val E1 = Endpoint.unsafeApply(publish &&& bind, U1)
  lazy val M1 = Monitoring.instance
  lazy val I1 = new Instruments(W, M1)

  lazy val U2 = new URI("tcp://127.0.0.1:7895")
  lazy val E2 = Endpoint.unsafeApply(publish &&& bind, U2)
  lazy val M2 = Monitoring.instance
  lazy val I2 = new Instruments(W, M1)

  // mirror to this instance
  lazy val MI = Monitoring.instance

  private def addInstruments(i: Instruments): Unit = {
    Clocks.instrument(i)
    JVM.instrument(i)
  }

  private def countKeys(m: Monitoring): Int =
    m.keys.compareAndSet(identity).run.get.filter(_.startsWith("previous")).length

  private def mirrorFrom(uri: URI): Unit ={
    println(s">>> mirroring from $uri")
    MI.mirrorAll(Mirror.from(S)
      )(uri, Map("uri" -> uri.toString)
      ).run.runAsync(_.fold(e => Ø.log.error(
        s"Error mirroring $uri: ${e.getMessage}"), identity))
  }

  override def beforeAll(){
    addInstruments(I1)
    addInstruments(I2)

    Publish.to(E1)(S,M1)
    Publish.to(E2)(S,M2)

    Thread.sleep(2.seconds.toMillis)

    mirrorFrom(U1)
    mirrorFrom(U2)

    Thread.sleep(W.toMillis*2)

  }

  override def afterAll(){
    println("==================================")
    MI.keys.get.run.foreach(println)
    println("==================================")

    println("Stoping the signal...")
    stop(S).run
  }

  "zeromq mirroring" should "pull values from the specified monitoring instance" in {
    (countKeys(M1) + countKeys(M2)) should equal (
      MI.keys.compareAndSet(identity).run.get.length)
  }

}

