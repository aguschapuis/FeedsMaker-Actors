package actors

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._
import scala.io.StdIn
import scala.concurrent.Await
import scala.concurrent.duration._
import java.util.concurrent.CompletionException

import akka.http.scaladsl.marshalling.ToResponseMarshallable
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

import scala.collection.mutable.ListBuffer
import feedaggregator.server.FeedAggregatorServer._



object Coordinator {
  //Protocolo para Actor Coordinator.
  case class DateTimeStr(since: Option[String])
  case class CreateActor(url: String)

  //Respuestas del actor Cordinator
  case class  UrlOk(url: String)
}

object Requester {
  //Protocolo para actor Requester
  case class AsyncRequest(url: String, since: Option[String])
  
  //Respuestas del actor Requester
  case class UrlNotFound(e:Throwable)
  case class FeedDone(feed:FeedInfo)
}


/*Actor coordinador el cual puede recivir dos clases de mensajes:
  - CreateActor: Se encarga de crear a todos los actores Requesters 
  que van a construir los feeds
  - DateTimeStr: Se llama a cada actor ya creado previamente para
  que estos contruyan los feeds. Estos se van poniendo en una lista
  (feedList) la cual es devuelta a quien la pidio en el route */

class Coordinator extends Actor{
  import Coordinator._
  import context.dispatcher

  def receive = {
    case CreateActor(url) =>
      val requestor = sender()
      asyncRequest(url) match {
        case Failure(exception) => requestor ! UrlNotFound(exception)
        case Success(rss) => 
          rss.onComplete {
            case Success(feed) =>
              val requester = context.actorOf(Props[Requester],
                                           url.replaceAll("/", "_"))
              requestor ! UrlOk(url)
            case Failure(e) => 
              requestor ! UrlNotFound(e)
          }
      }
          
    case DateTimeStr(since) =>
      val requestor = sender()
      implicit val timeout = Timeout(30.second)
      val feedlist: List[Future[Any]] = context.children.toList.map(actorref => {
        implicit val timeout = Timeout(10.second)
        actorref ? AsyncRequest(
                         actorref.path.name.replaceAll("_", "/"), since)
      })
      Future.sequence(feedlist) pipeTo sender()
  }
}


/*Actor requester que puede recibir una sola clase de mensaje:
  AsyncRequest: Se encarga de tomar el rss de el url pasado por parametro
  y con este construir el feed que tiene toda la informacion*/

class Requester extends Actor{
  import context.dispatcher
  import Requester._

  def asyncRequest(path: String): Try[Future[xml.Elem]] = {
    import dispatch._, Defaults._
    Try(dispatch.Http.default(dispatch.url(path) OK dispatch.as.xml.Elem))
  }

  def cmpDates(pubDate: String, since: Option[String]): Boolean = {
    since match {
      case None => true
      case Some(value) => {
        val formatSince: String = "yyyy-MM-dd'T'HH:mm:ss"
        val formatPubDate: String = "dd MMM yyyy HH:mm:ss z"
        val sinceFormatted = new SimpleDateFormat(formatSince).parse(value)
        val pubDateFormatted = new SimpleDateFormat(formatPubDate).parse(pubDate.split(", ")(1))
        pubDateFormatted.after(sinceFormatted) // true sii pubDateFormatted > sinceFormatted
      }
    }
  }
  
  def receive = {
    case AsyncRequest(url, since) =>
      val requestor = sender()
      asyncRequest(url) match {
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