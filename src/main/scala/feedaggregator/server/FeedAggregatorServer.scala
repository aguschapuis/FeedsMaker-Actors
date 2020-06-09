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
import akka.actor.{ActorRef, Props, PoisonPill}
//import java.lang.ProcessHandle.Info
import scala.util.Try
import scala.util.Success
import scala.util.Failure
import scala.concurrent.Future
import scala.collection.concurrent.FailedNode
import java.text.SimpleDateFormat

// import scala.util.Try
// import scala.concurrent.future
// import scala.concurrent.Future
// import scala.concurrent.ExecutionContext.Implicits.global
// import scala.concurrent.duration._
// import scala.util.Random

object FeedAggregatorServer {
  final case class ListFeedItem(list: List[FeedInfo])
  final case class FeedItem(title: String)
  final case class FeedInfo(title: String, description: Option[String], items: List[FeedItem])
  
  
  //Protocolo para el Actor Worker
  //final case class ItemPure(title: NodeSeq)
  //final case class ItemImproved(title: String)

  final case class InfoT(title: String, description: String, imagen: Int)
 
  case class SyncRequest(url: String)

  //Protocolo para Actor Coordinator.
  case class DateTimeStr(since: String)

  final case class Auxiliar(unic: Either[Throwable, xml.Elem]) 

  // Needed for Unmarshalling
  //implicit val listFeedItem = jsonFormat1(ListFeedItem)
  implicit val feedItem = jsonFormat1(FeedItem)
  implicit val feedInfo = jsonFormat3(FeedInfo)

  // TODO: This function needs to be moved to the right place
  def syncRequest(path: String): Future[xml.Elem] = {
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


  class Coordinator extends Actor{
     val requestor = sender()
     def receive = {
       case SyncRequest(url) =>
           implicit val executionContext = context.system.dispatcher
           val recibidor = context.actorOf(Props[Recibidor],url)
           
       case DateTimeStr(since) =>
         implicit val timeout = Timeout(5.second)
         println(context.children.map(actorref => {
         val feedInfo: Future[Any] = actorref ? SyncRequest(actorref.path.name)
         feedInfo}
         ))
         
         val list = context.children.map(actorref => {
                    val feedInfo: Future[Any] = actorref ? SyncRequest(actorref.path.name)
                    feedInfo.onComplete {
                      case Success(feed) =>
                        feed
                      case Failure(e) =>
                        e
                    }
         })
       requestor ! ListFeedItem(list.toList.asInstanceOf[List[FeedInfo]])
     }
  }

  class Recibidor extends Actor{
    def receive = {
      case SyncRequest(url) =>
          val requestor = sender
          syncRequest(url).onComplete {
            case Success(feed) =>
              val information = FeedInfo(
                  ((feed \ "channel") \ "title").headOption.map(_.text).get,
                  ((feed \ "channel") \ "description").headOption.map(_.text),
                  ((feed \ "channel") \ "item").map(item =>
                    FeedItem((item \ "title").headOption.map(_.text).get)
                    //val worker = context.actorOf(Props[WorkerItem])
                    //val itemOK: Future[Any] = worker ? ItemPure(item)
                    //worker ! PoisonPill
                    //itemOK
                  ).toList
                )
                
          requestor ! information
            case Failure(e) => 
              println(s"\nNo se esta realizando el syncRequest correctamente ---> $e\n")
          }
    }
  }




/*  class WorkerItem extends Actor{
      val requestor = sender()
      def receive = {
        case ItemPure(elem) =>
            FeedItem((item \ "title").headOption.map(_.text).get)
          requestor ! 
      }
    }
*/



  def main(args: Array[String]): Unit = {
    implicit val system = ActorSystem()
    // needed for the future flatMap/onComplete in the end
    implicit val executionContext = system.dispatcher

    val recibidor = system.actorOf(Props[Recibidor], "Recibidor")
    val coordinador = system.actorOf(Props[Coordinator], "Coordinador")

    val route =
      concat (
        path("") {
          complete("Hello, World!")
        },
        path("feed") {
          get {
            parameter("since".as[String]) { since =>
              val dateFormat = new SimpleDateFormat(since)
              complete(dateFormat.toPattern)
            }
            parameter("url".as[String]) { url =>
              implicit val timeout = Timeout(5.second)
              val feedInfo: Future[Any] = recibidor ? SyncRequest(url)
              onComplete(feedInfo) {
                case Success(feed) =>
                  recibidor ! PoisonPill
                  complete(feedInfo.mapTo[FeedInfo])
                case Failure(e) =>
                  recibidor ! PoisonPill
                  complete(StatusCodes.BadRequest -> s"Bad Request: ${e.getMessage}")
              }
            }
          }
        },
        path("feeds"){
          get{
            parameter("since".as[String]) { since =>
              val dateFormat = new SimpleDateFormat(since)
              implicit val timeout = Timeout(5.second)
              println(since)
              println(dateFormat)
              val listFeedItem: Future[Any] = coordinador ? DateTimeStr(dateFormat.toString)
              onComplete(listFeedItem) {
                case Success(feed) =>
                  complete(listFeedItem.mapTo[List[FeedItem]])
                case Failure(e) =>
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
