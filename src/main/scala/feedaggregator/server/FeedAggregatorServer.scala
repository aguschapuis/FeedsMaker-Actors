package feedaggregator.server

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._
import scala.io.StdIn
import scala.concurrent.Await
import scala.concurrent.duration._

object FeedAggregatorServer {
  final case class FeedItem(title: String)
  final case class FeedInfo(title: String, description: Option[String], items: List[FeedItem])

  // Needed for Unmarshalling
  implicit val feedItem = jsonFormat1(FeedItem)
  implicit val feedInfo = jsonFormat3(FeedInfo)

  // TODO: This function needs to be moved to the right place
  def syncRequest(path: String): Either[Throwable, xml.Elem] = {
    import dispatch._, Defaults._
    val rss = dispatch.Http.default(dispatch.url(path) OK dispatch.as.xml.Elem).either
    Await.result(rss, 15.seconds)
  }

  def main(args: Array[String]) {
    implicit val system = ActorSystem()
    // needed for the future flatMap/onComplete in the end
    implicit val executionContext = system.dispatcher

    val route =
      concat (
        path("") {
          complete("Hello, World!")
        },
        path("feed") {
          get {
            parameter("url".as[String]) { url =>
              syncRequest(url) match {
                case Right(feed) =>
                  val feedInfo = FeedInfo(
                    ((feed \ "channel") \ "title").headOption.map(_.text).get,
                    ((feed \ "channel") \ "description").headOption.map(_.text),
                    ((feed \ "channel") \\ "item").map(item =>
                      FeedItem((item \ "title").headOption.map(_.text).get)
                    ).toList
                  )
                  complete(feedInfo)
                case Left(e) =>
                  complete(StatusCodes.BadRequest -> s"Bad Request: ${e.getMessage}")
              }
            }
          }
        }
      )

    val bindingFuture = Http().bindAndHandle(route, "localhost", 8080)
    println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
    StdIn.readLine() // let it run until user presses return
    bindingFuture
      .flatMap(_.unbind()) // trigger unbinding from the port
      .onComplete(_ => system.terminate()) // and shutdown when done
  }
}
