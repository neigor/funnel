package intelmedia.ws.monitoring

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.io.{BufferedWriter, IOException, OutputStream, OutputStreamWriter}
import java.net.{InetSocketAddress, URL}
import scala.concurrent.duration._
import scalaz.concurrent.{Strategy, Task}
import scalaz.stream._

object Main extends App {
  MonitoringServer.start(Monitoring.default, 8081)

  import instruments._
  val c = counter("requests")
  val t = timer("response-time")
  val g = Process.awakeEvery(2 seconds).map { _ =>
    c.increment
    t.time(Thread.sleep(100))
  }.run.runAsync(_ => ())

  val M = Monitoring.instance
  val M2 = Monitoring.instance
  val I = new Instruments(5 minutes, M)
  MonitoringServer.start(M, 8083)
  MonitoringServer.start(M2, 8082)

  val c2 = I.counter("requests")
  val c3 = I.counter("requests2")
  val h = I.gauge("health", true, Units.Healthy)
  val g2 = Process.awakeEvery(2 seconds).map { _ =>
    c2.increment; c3.increment; h.set(true)
  }.take(5).run.runAsync(_ => ())

  // val k = mirror[Double]("http://localhost:8082", "now/requests", Some("now/requests-clone"))
  M2.mirrorAll(SSE.readEvents)(
    new URL("http://localhost:8083/stream"),
    "node1/" + _
  ).run.runAsync(_ => ())
  M2.decay(Key.StartsWith("node1"))(Events.every(10 seconds)).run

  //
  // application that given a Process[Task,URL], mirr
  // def mirrorAll(nodes: Process[Task,(Int,URL)])(health: (Int, List[Key[Boolean]]) => Metric[Boolean]): Unit = ???

  // mirrorAll("http://node1:8081/now", Some("node1/"))
  // mirrorAll("http://node2:8081/now", Some("node1/"))
  // mirrorAll("http://node3:8081/now", Some("node1/"))

  // k.map(_ * 10).publishEvery(5 seconds)("now/request-clone-times-10", Units.Count)
  readLine()
}

object MonitoringServer {

  type Log = String => Unit

  /**
   * `/`: self-describing list of available resources
   * `/keys`: stream of changing list of keys
   * `/now`: snapshot of all metrics whose labels begin with 'now'
   * `/previous`: snapshot of all metrics whose labels begin with 'previous'
   * `/sliding`: snapshot of all metrics whose labels begin with 'sliding'
   * `/<prefix>`: snapshot of all metrics whose labels begin with 'prefix' (except for 'stream' and 'keys', which are reserved)
   * `/stream`: stream of all metrics
   * `/stream/keys`: stream of changing list of keys
   * `/stream/<keyid>`: stream of metrics for the given key
   * `/stream/<prefix>`: stream of metrics whose labels start with 'prefix'
   */
  def start(M: Monitoring, port: Int = 8080, log: Log = println): () => Unit = {
    val server = HttpServer.create(new InetSocketAddress(port), 0)
    server.setExecutor(Monitoring.serverPool)
    server.createContext("/", handler(M, log))
    server.start()
    log("server started on port: " + port)
    () => server.stop(0)
  }

  private[monitoring] def handler(M: Monitoring, log: Log) = new HttpHandler {
    def handle(req: HttpExchange): Unit = try {
      log("path: " + req.getRequestURI.getPath)
      val path = req.getRequestURI.getPath match {
        case "/" => List()
        case p => p.split("/").toList.tail
      }
      path match {
        case Nil => handleRoot(req)
        case "keys" :: tl => handleKeys(M, tl.mkString("/"), req, log)
        case "stream" :: "keys" :: Nil => handleKeysStream(M, req, log)
        case "stream" :: tl => handleStream(M, tl.mkString("/"), req, log)
        case now => handleNow(M, now.mkString("/"), req, log)
      }
    }
    catch {
      case e: Exception => log("fatal error: " + e)
    }
    finally req.close

    def handleKeys(M: Monitoring, prefix: String, req: HttpExchange, log: Log): Unit = {
      import JSON._; import argonaut.EncodeJson._
      val ks = M.keys.continuous.once.runLastOr(List()).run.filter(_.startsWith(prefix))
      val respBytes = JSON.prettyEncode(ks).getBytes
      req.getResponseHeaders.set("Content-Type", "application/json")
      req.getResponseHeaders.set("Access-Control-Allow-Origin", "*")
      req.sendResponseHeaders(200, respBytes.length)
      req.getResponseBody.write(respBytes)
    }

    def handleKeysStream(M: Monitoring, req: HttpExchange, log: Log): Unit = {
      req.getResponseHeaders.set("Content-Type", "text/event-stream")
      req.getResponseHeaders.set("Access-Control-Allow-Origin", "*")
      req.sendResponseHeaders(200, 0L) // 0 as length means we're producing a stream
      val sink = new BufferedWriter(new OutputStreamWriter(req.getResponseBody))
      SSE.writeKeys(M.distinctKeys, sink)
    }

    def handleStream(M: Monitoring, prefix: String, req: HttpExchange, log: Log): Unit = {
      req.getResponseHeaders.set("Content-Type", "text/event-stream")
      req.getResponseHeaders.set("Access-Control-Allow-Origin", "*")
      req.sendResponseHeaders(200, 0L) // 0 as length means we're producing a stream
      val events = Monitoring.subscribe(M)(Key.StartsWith(prefix))(log = log)
      val sink = new BufferedWriter(new OutputStreamWriter(req.getResponseBody))
      SSE.writeEvents(events, sink)
    }

    def handleNow(M: Monitoring, label: String, req: HttpExchange, log: Log): Unit = {
      import JSON._; import argonaut.EncodeJson._
      val m = Monitoring.snapshot(M).run
      val respBytes =
        JSON.prettyEncode(m.filterKeys(_.startsWith(label)).values.toList).getBytes
      req.getResponseHeaders.set("Content-Type", "application/json")
      req.getResponseHeaders.set("Access-Control-Allow-Origin", "*")
      req.sendResponseHeaders(200, respBytes.length)
      req.getResponseBody.write(respBytes)
    }

    def handleRoot(req: HttpExchange): Unit = {
      req.sendResponseHeaders(200, helpHTML.length)
      req.getResponseBody.write(helpHTML.getBytes)
    }
  }

  val helpHTML = """
  |<html>
  |<body>
  |<p>Monitoring resources:</p>
  |<ul>
  |<li><a href="/keys">/keys</a>: Current snapshot of all metric keys.</li>
  |<li><a href="/keys/id">/keys</a>: Current snapshot of all keys prefixed by 'id'.</li>
  |<li><a href="/now">/now</a>: Current values for all metrics prefixed by 'now'. </li>
  |<li><a href="/previous">/previous</a>: Current values for all metrics prefixed by 'previous'.</li>
  |<li><a href="/sliding">/sliding</a>: Current values for all metrics prefixed by 'sliding'.</li>
  |<li><a href="/stream/keys">/stream/keys</a>: Full stream of all metric keys.</li>
  |<li><a href="/stream">/stream</a>: Full stream of all metrics.</li>
  |</ul>
  |</body>
  |</html>
  """.stripMargin

}
