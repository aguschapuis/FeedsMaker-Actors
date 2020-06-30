package feedaggregator.server

import actors._
import actors.Coordinator._
import actors.Requester._

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

import akka.pattern.{ask, pipe}
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

// import scala.collection.mutable

import akka.http.scaladsl.marshalling.ToResponseMarshallable
//import java.io.ObjectInputFilter.Status


object FeedAggregatorServer {

 
  implicit val feeditem = jsonFormat4(FeedItem)
  implicit val feedinfo = jsonFormat3(FeedInfo)
  implicit val listfeedItem = jsonFormat1(ListFeedItem)
  implicit val feeddone = jsonFormat1(FeedDone)
  
  def main(args: Array[String]): Unit = {
    
    implicit val system = ActorSystem()
    // needed for the future flatMap/onComplete in the end
    implicit val executionContext = system.dispatcher
    val requester = system.actorOf(Props[Requester], "Requester")
    val coordinador = system.actorOf(Props[Coordinator], "Coordinador")
    var url_counter = 0
    // val urls_list = mutable.ListBuffer[(Int,String)]()
    val route =
        concat (
          path("") {
            complete("Hello, World!")
          },
          path("feed") {
            get {
              parameter("url".as[String], "since".?) { (url, since) =>
                implicit val timeout = Timeout(10.second)
                val feedInfo: Future[Any] = requester ? AsyncRequest(url, since)
                onComplete(feedInfo) {
                  case Success(feed) =>
                    feed match {
                      case UrlNotFound(e) => 
                        if(e.getMessage contains "java.net.UnknownHostException"){
                          complete(StatusCodes.NotFound -> 
                                    s"$url : Unkown name o service")
                        }
                        else if(e.getMessage contains "response status: 404"){
                          complete(StatusCodes.NotFound -> 
                                    s"Not Found: $url")
                        }
                        else if(e.getMessage contains "could not be parsed"){
                          complete(StatusCodes.BadRequest -> 
                                    s"The url has an incorrect format")
                        }
                        else if(e.getMessage contains "java.text.ParseException"){
                          complete(StatusCodes.BadRequest -> 
                                    s"Invalid date format")
                        }
                        else{
                          complete(StatusCodes.BadRequest -> 
                                    "Dispatch error: Bad request")
                        }
                      case FeedDone(feed) => 
                        complete(feed.asInstanceOf[FeedInfo])
                    }
                  case Failure(e) => 
                    complete(StatusCodes.BadRequest -> 
                                  s"Failure: ${e.getMessage}")
                  }
                
              }
            }
          },
          path("subscribe"){
            post{
              entity(as[Map[String,String]]) { url => 
                implicit val timeout = Timeout(5.second)
                val urlFinal = url.get("url").get
                val creador: Future[Any] = coordinador ? CreateActor(urlFinal)
                onComplete(creador) {
                  case Success(set) =>
                    set match {
                      case UrlOk(url) =>  
                        // urls_list += (url_counter -> url)
                        url_counter += 1
                        complete(StatusCodes.OK -> s"The url: $url is added to the feed list")
                      case UrlNotFound(e) => 
                        if(e.getMessage contains "java.net.UnknownHostException"){
                          complete(StatusCodes.NotFound -> 
                                    s"$urlFinal : Unkown name o service")
                        }
                        else if(e.getMessage contains "response status: 404"){
                          complete(StatusCodes.NotFound -> 
                                    s"Not Found: $urlFinal")
                        }
                        else if(e.getMessage contains "could not be parsed"){
                          complete(StatusCodes.BadRequest -> 
                                    s"The url has an incorrect format")
                        }
                        else{
                          complete(StatusCodes.BadRequest -> 
                                    "Dispatch error: Bad request")
                        }
                    }
                  case Failure(e) => 
                    complete(StatusCodes.BadRequest -> 
                                  s"Failure: ${e.getMessage}")
                }  
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
                    if(url_counter == 0) {
                      complete(StatusCodes.NotFound -> "There are no subcribed Urls")
                    }
                    else complete(feed.asInstanceOf[List[FeedDone]])
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
