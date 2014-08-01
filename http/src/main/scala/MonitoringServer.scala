package intelmedia.ws.funnel
package http

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.io.{BufferedWriter, IOException, OutputStream, OutputStreamWriter}
import java.net.{InetSocketAddress, URL}
import scala.concurrent.duration._
import scalaz.concurrent.{Strategy, Task}
import scalaz.stream._

object MonitoringServer {

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
  def start(M: Monitoring, port: Int = 8080): MonitoringServer = {
    val svr = (new MonitoringServer(M, port))
    svr.start()
    svr
  }

  @deprecated("""MonitoringServer.start no longer takes a `log` argument.
    Use Monitoring.instance to create your Monitoring instance
    if you want to specify a logger.""", "1.3")
  def start(M: Monitoring, port: Int, log: String => Unit): MonitoringServer = start(M, port)

  @deprecated("""MonitoringServer.start no longer takes a `log` argument.
    Use Monitoring.instance to create your Monitoring instance
    if you want to specify a logger.""", "1.3")
  def start(M: Monitoring, log: String => Unit): MonitoringServer = start(M)
}

class MonitoringServer(M: Monitoring, port: Int) extends ControlServer {
  private[funnel] val (mirroringQueue,commands) =
    async.queue[Command](Strategy.Executor(Monitoring.serverPool))

  private val server = HttpServer.create(new InetSocketAddress(port), 0)

  def start(): Unit = {
    server.setExecutor(Monitoring.serverPool)
    val _ = server.createContext("/", handleMetrics(M))
    server.start()
    M.log("server started on port: " + port)
  }

  def stop(): Unit = server.stop(0)

  protected def handleIndex(req: HttpExchange): Unit = {
    req.sendResponseHeaders(200, helpHTML.length)
    req.getResponseBody.write(helpHTML.getBytes)
  }

  protected def handleStream(M: Monitoring, prefix: String, req: HttpExchange): Unit = {
    req.getResponseHeaders.set("Content-Type", "text/event-stream")
    req.getResponseHeaders.set("Access-Control-Allow-Origin", "*")
    req.sendResponseHeaders(200, 0L) // 0 as length means we're producing a stream
    val events = Monitoring.subscribe(M)(Key.StartsWith(prefix))
    val sink = new BufferedWriter(new OutputStreamWriter(req.getResponseBody))
    SSE.writeEvents(events, sink)
  }

  protected def handleKeys(M: Monitoring, prefix: String, req: HttpExchange): Unit = {
    import JSON._; import argonaut.EncodeJson._
    val ks = M.keys.continuous.once.runLastOr(List()).run.filter(_.startsWith(prefix))
    val respBytes = JSON.prettyEncode(ks).getBytes
    req.getResponseHeaders.set("Content-Type", "application/json")
    req.getResponseHeaders.set("Access-Control-Allow-Origin", "*")
    req.sendResponseHeaders(200, respBytes.length)
    req.getResponseBody.write(respBytes)
  }

  protected def handleKeysStream(M: Monitoring, req: HttpExchange): Unit = {
    req.getResponseHeaders.set("Content-Type", "text/event-stream")
    req.getResponseHeaders.set("Access-Control-Allow-Origin", "*")
    req.sendResponseHeaders(200, 0L) // 0 as length means we're producing a stream
    val sink = new BufferedWriter(new OutputStreamWriter(req.getResponseBody))
    SSE.writeKeys(M.distinctKeys, sink)
  }

  protected def handleNow(M: Monitoring, label: String, req: HttpExchange): Unit = {
    import JSON._; import argonaut.EncodeJson._
    val m = Monitoring.snapshot(M).run
    val respBytes =
      JSON.prettyEncode(m.filterKeys(_.startsWith(label)).values.toList).getBytes
    req.getResponseHeaders.set("Content-Type", "application/json")
    req.getResponseHeaders.set("Access-Control-Allow-Origin", "*")
    req.sendResponseHeaders(200, respBytes.length)
    req.getResponseBody.write(respBytes)
  }

  protected def handleAddMirroringURLs(M: Monitoring, req: HttpExchange): Unit = {
    import JSON._; import argonaut.Parse;

    post(req){ json =>
      Parse.decodeEither[List[Bucket]](json).fold(
        error => flush(400, error.toString, req),
        blist => {
          blist.flatMap(b => b.urls.map(u => Mirror(new URL(u), b.label))
            ).foreach(mirroringQueue.enqueue)

          flush(202, Array.empty[Byte], req)
        }
      )
    }
  }

  protected def handleHaltMirroringURLs(M: Monitoring, req: HttpExchange): Unit = {
    import JSON._; import argonaut.Parse;

    post(req){ json =>
      Parse.decodeEither[List[String]](json).fold(
        error => flush(400, error.toString, req),
        list => {
          list.map(u => Discard(new URL(u))
            ).foreach(mirroringQueue.enqueue)
          flush(202, Array.empty[Byte], req)
        }
      )
    }
  }

  private def handleAudit(M: Monitoring, req: HttpExchange): Unit = {
    import JSON._; import argonaut._, Argonaut._;

    M.audit.attemptRun.fold(
      err => {
        val respBytes = err.getMessage.toString.getBytes("UTF-8")
        req.sendResponseHeaders(500, respBytes.length)
        req.getResponseBody.write(respBytes)
      },
      list => flush(200,
        list.map(t => Audit(t._1, t._2)).asJson.nospaces.getBytes, req)
    )
  }

  private def post(req: HttpExchange)(f: String => Unit): Unit = {
    import scala.io.Source
    if(req.getRequestMethod.toLowerCase == "post"){
      // as the payloads here will be small, lets just turn it into a string
      val json = Source.fromInputStream(req.getRequestBody).mkString
      f(json)
    } else flush(405, "Request method not allowed.", req)
  }

  private def flush(status: Int, body: String, req: HttpExchange): Unit =
    flush(status, body.getBytes, req)

  private def flush(status: Int, body: Array[Byte], req: HttpExchange): Unit = {
    req.sendResponseHeaders(status,body.length)
    req.getResponseBody.write(body)
  }

  protected def handleMetrics(M: Monitoring) = new HttpHandler {
    def handle(req: HttpExchange): Unit = try {
      M.log("path: " + req.getRequestURI.getPath)
      val path = req.getRequestURI.getPath match {
        case "/" => Nil
        case p   => p.split("/").toList.tail
      }
      path match {
        case Nil                       => handleIndex(req)
        case "audit"  :: Nil           => handleAudit(M, req)
        case "halt"   :: Nil           => handleHaltMirroringURLs(M, req)
        case "mirror" :: Nil           => handleAddMirroringURLs(M, req)
        case "keys"   :: tl            => handleKeys(M, tl.mkString("/"), req)
        case "stream" :: "keys" :: Nil => handleKeysStream(M, req)
        case "stream" :: tl            => handleStream(M, tl.mkString("/"), req)
        case now                       => handleNow(M, now.mkString("/"), req)
      }
    }
    catch {
      case e: Exception => M.log("fatal error: " + e)
    }
    finally req.close
  }

  val helpHTML = s"""
    |<!DOCTYPE html>
    |<html lang="en">
    |  <head>
    |    <link rel="stylesheet" href="//maxcdn.bootstrapcdn.com/bootstrap/3.2.0/css/bootstrap.min.css">
    |    <link rel="stylesheet" href="//maxcdn.bootstrapcdn.com/bootstrap/3.2.0/css/bootstrap-theme.min.css">
    |    <script src="//maxcdn.bootstrapcdn.com/bootstrap/3.2.0/js/bootstrap.min.js"></script>
    |    <title>Funnel &middot; ${BuildInfo.version} &middot; ${BuildInfo.gitRevision}</title>
    |    <style type="text/css">
    |    /* Space out content a bit */
    |    body {
    |      padding-top: 20px;
    |      padding-bottom: 20px;
    |    }
    |
    |    /* Everything but the jumbotron gets side spacing for mobile first views */
    |    .header,
    |    .marketing,
    |    .footer {
    |      padding-right: 15px;
    |      padding-left: 15px;
    |    }
    |
    |    /* Custom page header */
    |    .header {
    |      border-bottom: 1px solid #e5e5e5;
    |    }
    |    /* Make the masthead heading the same height as the navigation */
    |    .header h3 {
    |      padding-bottom: 19px;
    |      margin-top: 0;
    |      margin-bottom: 0;
    |      line-height: 40px;
    |    }
    |
    |    /* Custom page footer */
    |    .footer {
    |      padding-top: 19px;
    |      color: #777;
    |      border-top: 1px solid #e5e5e5;
    |    }
    |
    |    /* Customize container */
    |    @media (min-width: 768px) {
    |      .container {
    |        max-width: 730px;
    |      }
    |    }
    |    .container-narrow > hr {
    |      margin: 30px 0;
    |    }
    |
    |    /* Main marketing message and sign up button */
    |    .jumbotron {
    |      text-align: center;
    |      border-bottom: 1px solid #e5e5e5;
    |    }
    |    .jumbotron .btn {
    |      padding: 14px 24px;
    |      font-size: 21px;
    |    }
    |
    |    /* Supporting marketing content */
    |    .marketing {
    |      margin: 40px 0;
    |    }
    |    .marketing p + h4 {
    |      margin-top: 28px;
    |    }
    |
    |    /* Responsive: Portrait tablets and up */
    |    @media screen and (min-width: 768px) {
    |      /* Remove the padding we set earlier */
    |      .header,
    |      .marketing,
    |      .footer {
    |        padding-right: 0;
    |        padding-left: 0;
    |      }
    |      /* Space out the masthead */
    |      .header {
    |        margin-bottom: 30px;
    |      }
    |      /* Remove the bottom border on the jumbotron for visual effect */
    |      .jumbotron {
    |        border-bottom: 0;
    |      }
    |    }
    |    </style>
    |  </head>
    |
    |  <body>
    |
    |    <div class="container">
    |      <div class="header">
    |        <ul class="nav nav-pills pull-right">
    |          <li><a href="http://github-media.sc.intel.com/pages/intelmedia/funnel/">About</a></li>
    |          <li><a href="mailto:timothy.m.perrett@oncue.com">Contact</a></li>
    |        </ul>
    |        <h3 class="text-muted">Funnel Control Panel</h3>
    |      </div>
    |
    |      <div class="jumbotron">
    |        <p class="lead">Once this system is deployed, visulize the instrumented cluster data in real-time dashboards which you can save to your github account. Simply click the button below to get started.</p>
    |        <p><a class="btn btn-lg btn-primary" href="http://github-media.sc.intel.com/login/oauth/authorize?client_id=8af95c578127c236c370&amp;scope=gist" role="button">Login with Github</a></p>
    |      </div>
    |
    |      <div class="row marketing">
    |        <div class="col-lg-6">
    |          <h4>Metric Resources</h4>
    |          <p><a href="/keys">GET /keys</a>: Display the current snapshot of all keys registred with the monitoring instance.</p>
    |          <p><a href="/keys/prefix">GET /keys/prefix</a>: Display the current snapshot of all keys prefixed by the word 'prefix'.</p>
    |
    |          <h4>Window Resources</h4>
    |          <p><a href="/now">GET /now</a>: Current values for all metrics prefixed by 'now'.</p>
    |          <p><a href="/previous">GET /previous</a>: Current values for all metrics prefixed by 'previous'.</p>
    |          <p><a href="/sliding">GET /sliding</a>: Current values for all metrics prefixed by 'sliding'.</p>
    |        </div>
    |
    |        <div class="col-lg-6">
    |          <h4>Operations Resources</h4>
    |          <p><a href="/mirror">POST /now</a>: Dynamically mirror metrics from other funnel(s).</p>
    |          <p><a href="/halt">POST /halt</a>: Stop mirroring metrics from the given funnel URLs).</p>
    |        </div>
    |      </div>
    |
    |      <div class="footer">
    |        <p>&copy; Verizon OnCue 2014</p>
    |      </div>
    |
    |    </div>
    |  </body>
    |</html>
  """.stripMargin

}