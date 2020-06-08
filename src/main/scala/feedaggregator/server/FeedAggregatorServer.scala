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
import scala.annotation.compileTimeOnly


object FeedAggregatorServer {
  final case class FeedItem(title: String,
                            link: String,
                            description: Option[String],
                            pubDate: String
                            )
  final case class FeedInfo(title: String,
                            description: Option[String],
                            items: List[FeedItem]
                            )
  
  def cmpDates(pubDate: String, since: Option[String]): Boolean = {
    since match {
      case None => true
      case Some(value) => {
        val formatSince: String = "yyyy-MM-dd'T'HH:mm:ss"
        val formatPubDate: String = "dd MMM yyyy HH:mm:ss z"
        val sinceFormatted = new SimpleDateFormat(formatDate).parse(value)
        val pubDateFormatted = new SimpleDateFormat(formatPubDate).parse(pubDate.split(", ")(1))
        pubDateFormat.before(sinceFormat) // true sii pubDateFormat < sinceFormat
      }
    }
  }
  
  //Protocolo para el Actor Worker
  //final case class ItemPure(title: NodeSeq)
  //final case class ItemImproved(title: String)

  final case class InfoT(title: String, description: String, imagen: Int)
 
  case class SyncRequest(url: String, since: Option[String])

  //Protocolo para Actor Coordinator.
  case class DateTimeStr(since: String)

  final case class Auxiliar(unic: Either[Throwable, xml.Elem]) 

  // Needed for Unmarshalling
  implicit val feedItem = jsonFormat4(FeedItem)
  implicit val feedInfo = jsonFormat3(FeedInfo)

  // TODO: This function needs to be moved to the right place
  def syncRequest(path: String): Future[xml.Elem] = {
    val rss = dispatch.Http.default(dispatch.url(path) OK dispatch.as.xml.Elem)
    rss
  }


  class Coordinator extends Actor{
     val requestor = sender()
     def receive = {
       case SyncRequest(url, since) =>
          implicit val executionContext = context.system.dispatcher
          val recibidor = context.actorOf(Props[Recibidor],url)
           
       case DateTimeStr(since) =>
         implicit val timeout = Timeout(5.second)
         println("Children " + context.children)
         /*val list = context.children.fold(List()) (list, actorref) => 
           val feedInfo: Future[Any] = actorref ? SyncRequest(self.path.name)
           onComplete(feedInfo) {
             case Success(feed) =>
               requestor ! PoisonPill
               List(feed) ++ list
             case Failure(e) =>
               requestor ! PoisonPill
               list
           }
         
         requestor ! list*/
     }
  }

  class Recibidor extends Actor{
    def receive = {
      case SyncRequest(url, since) =>
          val requestor = sender
          syncRequest(url).onComplete {
            case Success(feed) =>
              val information = FeedInfo(
                  ((feed \ "channel") \ "title").headOption.map(_.text).get,
                  ((feed \ "channel") \ "description").headOption.map(_.text),
                  ((feed \ "channel") \ "item").map(item =>
                    FeedItem(
                      (item \ "title").headOption.map(_.text).get,
                      (item \ "link").headOption.map(_.text).get,
                      (item \ "description").headOption.map(_.text),
                      (item \ "pubDate").headOption.map(_.text).get
                    )
                    //val worker = context.actorOf(Props[WorkerItem])
                    //val itemOK: Future[Any] = worker ? ItemPure(item)
                    //worker ! PoisonPill
                    //itemOK
                  ).toList.filter(item => cmpDates(item.pubDate, since))
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
            parameter("url".as[String], "since".?) { (url, since) =>
              implicit val timeout = Timeout(5.second)
              val feedInfo: Future[Any] = recibidor ? SyncRequest(url, since)
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
        path("subscribe"){
          post{
            entity(as[String]) { url => 
              implicit val timeout = Timeout(5.second)
              coordinador ? SyncRequest(url, since)
              complete(s"Se agrego un nuevo url: ${url} a la lista de feeds") 
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
              val feedInfo: Future[Any] = coordinador ? DateTimeStr(dateFormat.toString)
              onComplete(feedInfo) {
                case Success(feed) =>
                  complete(feedInfo.mapTo[FeedInfo])
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
