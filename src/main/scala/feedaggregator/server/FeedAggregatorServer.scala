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

import akka.http.scaladsl.marshalling.ToResponseMarshallable

import akka.pattern.ask
import akka.util.Timeout

import dispatch._, Defaults._

import akka.actor.Actor
import akka.actor.{ActorRef, Props}
import java.lang.ProcessHandle.Info
import scala.util.Try
import scala.util.Success
import scala.util.Failure
import scala.concurrent.Future
import scala.collection.concurrent.FailedNode

// import scala.util.Try
// import scala.concurrent.future
// import scala.concurrent.Future
// import scala.concurrent.ExecutionContext.Implicits.global
// import scala.concurrent.duration._
// import scala.util.Random

object FeedAggregatorServer {
  final case class FeedItem(title: String, link: Option[String])
  final case class FeedInfo(title: String, description: Option[String], items: List[FeedItem])

  final case class InfoT(title: String, description: String, imagen: Int)

  sealed trait Information
  case class SyncRequest(url: String)
  case class MakeInfo(feed: xml.Elem)

  final case class Auxiliar(unic: Either[Throwable, xml.Elem]) 

  // Needed for Unmarshalling
  implicit val feedItem = jsonFormat2(FeedItem)
  implicit val feedInfo = jsonFormat3(FeedInfo)

  // TODO: This function needs to be moved to the right place
  def syncRequest(path: String): Future[xml.Elem] = {
    import dispatch._, Defaults._
    val rss = dispatch.Http.default(dispatch.url(path) OK dispatch.as.xml.Elem)
    rss
  }

  // i) ustedes reciben un request del usuario a un endpoint, por ejemplo /feed. 
  // ii) ustedes hacen un request a la URL para que les devuelvan el feed de un diario X
  // iii) ustedes procesan ese feed para extrar distintos items, de los cuales sacan sólo algunos campos
  // iv) compilan una respuesta y se la mandan al usuario

  // Pueden hacer que el proceso  de los distintos items dentro de un mismo feed sea concurrente, 
  // pero me parece que van a ganar más tiempo si hacen que el proceso de hacer el request y
  // quedarse esperando a que el endpoint del diario responda
  
  class Recibidor extends Actor{
    def receive = {
      case SyncRequest(url) =>
        syncRequest(url).onComplete {
          case Success(value) => sender ! value
          case Failure(e) => sender ! e
        }
      case MakeInfo(feed) =>
          val information = FeedInfo(
                  ((feed \ "channel") \ "title").headOption.map(_.text).get,
                  ((feed \ "channel") \ "description").headOption.map(_.text),
                  ((feed \ "channel") \\ "item").map(item =>
                    FeedItem((item \ "title").headOption.map(_.text).get,
                              (item \ "link").headOption.map(_.text))
                  ).toList
                )
          sender ! information
      case _ => sender ! new Exception
    }
  }

  def main(args: Array[String]): Unit = {
    implicit val system = ActorSystem()
    // needed for the future flatMap/onComplete in the end
    implicit val executionContext = system.dispatcher

    val recibidor = system.actorOf(Props[Recibidor], "Recibidor")
    
    val route =
      concat (
        path("") {
          complete("Hello, World!")
        },
        path("feed") {
          get {
            parameter("url".as[String]) { url =>
              implicit val timeout = Timeout(5.second)
              val f: Future[Any] = recibidor ? SyncRequest(url)
                
                onComplete(f) {
                  case Success(feed: xml.Elem)=>
                     val aux = recibidor ? MakeInfo(feed)
                    complete(aux.mapTo[FeedInfo])
                  case Failure(e) =>
                    complete(StatusCodes.BadRequest -> s"Bad Request: ${e.getMessage}")
                  case _ => complete("Nothing")
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
