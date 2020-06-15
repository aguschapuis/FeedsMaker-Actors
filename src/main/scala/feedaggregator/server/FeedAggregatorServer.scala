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


import akka.actor.Actor
import akka.actor.{ActorRef, Props, PoisonPill}
import scala.util.Try
import scala.util.Success
import scala.util.Failure
import scala.concurrent.Future
import scala.collection.concurrent.FailedNode
import java.text.SimpleDateFormat
import java.lang.String
import scala.annotation.compileTimeOnly

import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server._
import Directives._
import java.sql.Date
import java.util.concurrent.CompletionException


object FeedAggregatorServer {

  final case class ListFeedItem(list: List[FeedInfo])
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
        val sinceFormatted = new SimpleDateFormat(formatSince).parse(value)
        val pubDateFormatted = new SimpleDateFormat(formatPubDate).parse(pubDate.split(", ")(1))
        pubDateFormatted.before(sinceFormatted) // true sii pubDateFormatted < sinceFormatted
      }
    }
  }


  final case class InfoT(title: String, description: String, imagen: Int)
 
  case class SyncRequest(url: String, since: Option[String])
  
  case class UrlNotFound(e:Throwable)
  case class FeedDone(e:FeedInfo)
  //Protocolo para Actor Coordinator.
  case class DateTimeStr(since: Option[String])

  //implicit val listFeedItem = jsonFormat1(ListFeedItem)
  implicit val feedItem = jsonFormat4(FeedItem)
  implicit val feedInfo = jsonFormat3(FeedInfo)
  implicit val listfeedItem = jsonFormat1(ListFeedItem)
  // TODO: This function needs to be moved to the right place
  def syncRequest(path: String): Try[Future[xml.Elem]] = {
    import dispatch._, Defaults._
    Try(dispatch.Http.default(dispatch.url(path) OK dispatch.as.xml.Elem))
  }


  class Coordinator extends Actor{
     import context.dispatcher
     val requestor = sender()
     def receive = {
       case SyncRequest(url, since) =>
          implicit val executionContext = context.system.dispatcher
          val requester = context.actorOf(Props[Requester],
                                          url.replaceAll("/", "_"))
           
       case DateTimeStr(since) =>
          println(context.children)
          implicit val timeout = Timeout(10.second)
          val list: Future[List[FeedInfo]] = Future( context.children.toList.map(actorref => {
            var feedBack : Any = null
            implicit val timeout = Timeout(10.second)
            val feedInfo = actorref ? SyncRequest(
                            actorref.path.name.replaceAll("_", "/"), since)
            feedInfo.onComplete {
              case Success(feed) =>
                // println("feed adentro del succes (datetiemstr): " + feed)
                feedBack = feed
                feedBack
                // println("feedback pisado:  " + feedBack)
                case Failure(e) =>
                e
              }
              println("\n\n\n\n\nfeedback despues :   " + feedBack)
            feedBack.asInstanceOf[FeedInfo]
          }))
          println(list)

          list.onComplete {
            case Success(finallist) => sender() ! ListFeedItem(finallist)
            case Failure(e) => sender() ! e
          }
     
      }
     }  

  class Requester extends Actor{
    import context.dispatcher
    def receive = {
      case SyncRequest(url, since) =>
          val requestor = sender()
          syncRequest(url) match {
            case Failure(exception) => requestor ! UrlNotFound(exception)
            case Success(value) => 
            value.onComplete {
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
                  ).toList.filter(item => cmpDates(item.pubDate, since))
                )
 
                requestor ! FeedDone(information)
              case Failure(e) => 
                requestor ! UrlNotFound(e)
          }
          }
        
    }
  }
  
  def main(args: Array[String]): Unit = {
    
    implicit val system = ActorSystem()
    // needed for the future flatMap/onComplete in the end
    implicit val executionContext = system.dispatcher
    val requester = system.actorOf(Props[Requester], "Requester")
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
                val feedInfo: Future[Any] = requester ? SyncRequest(url, since)
                onComplete(feedInfo) {
                  case Success(feed) =>
                    feed match {
                      case UrlNotFound(e) => 
                        println(s"El url not doundf e es :   $e")
                        if(e.getMessage contains "java.net.UnknownHostException"){
                          complete(StatusCodes.NotFound -> s"$url : Unkown name o service")
                        }
                        else if(e.getMessage contains "response status: 404"){
                          complete(StatusCodes.NotFound -> s"Not Found: $url")
                        }
                        else if(e.getMessage contains "could not be parsed"){
                          complete(StatusCodes.BadRequest -> s"The url has an incorrect format")
                        }
                        else{
                          complete(StatusCodes.BadRequest -> "Dispatch error: Bad request")
                        }
                      case FeedDone(feed) => 
                        complete(feed)
                    }
                  case Failure(e) => 
                    complete(StatusCodes.BadRequest -> s"Failure: ${e.getMessage}")
                  }
                
              }
            }
          },
          path("subscribe"){
            post{
              entity(as[Map[String,String]]) { url =>  //as[String] 
                implicit val timeout = Timeout(5.second)
                val urlFinal = url.get("url").get
                coordinador ! SyncRequest(urlFinal, None)
                complete(StatusCodes.OK -> s"The url: $urlFinal is added to the feed list") 
              }
            } 
          },
          path("feeds"){
            get{
              parameter("since".?) {since =>
                  implicit val timeout = Timeout(10.second)
                  val listFeedItem: Future[Any] = coordinador ? DateTimeStr(since)
                  onComplete(listFeedItem) {
                    case Success(feed) =>
                      complete(listFeedItem.mapTo[ListFeedItem])
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
