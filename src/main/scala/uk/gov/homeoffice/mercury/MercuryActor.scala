package uk.gov.homeoffice.mercury

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import uk.gov.homeoffice.aws.s3.S3
import uk.gov.homeoffice.aws.sqs.Message
import uk.gov.homeoffice.web.WebService

object MercuryActor {
  def props(s3: S3, credentials: Credentials, webService: WebService)(implicit listeners: Seq[ActorRef] = Seq.empty[ActorRef]) = Props {
    new MercuryActor(s3, credentials, webService)
  }
}

class MercuryActor(val s3: S3, credentials: Credentials, implicit val webService: WebService)(implicit listeners: Seq[ActorRef] = Seq.empty[ActorRef]) extends Actor with ActorLogging {
  implicit val ec = context.dispatcher

  override def preStart(): Unit = {
    super.preStart()
    self ! AuthorizeMercury
  }

  override def receive: Receive = {
    case AuthorizeMercury =>
      Mercury authorize credentials map { webService =>
        context become authorized(Mercury(s3, webService))
        listeners foreach { _ ! Authorized }
        context.system.scheduler.scheduleOnce(10 seconds, self, Publish)
      } recover {
        case t: Throwable =>
          log.error(s"Failed to authorize Mercury with webservice ${webService.host} because of ${t.getMessage}")
          context.system.scheduler.scheduleOnce(10 seconds, self, AuthorizeMercury)
      }

    case _: Message =>
      val warning = "Received a message but Mercury is not authorized to perform publication"
      log.warning(warning)
      sender() ! warning
  }

  def authorized(mercury: Mercury): Receive = {
    case Publish =>
      val client = sender()

      mercury.publish.map { publications =>
        client ! publications
        listeners foreach { _ ! publications }
        context.system.scheduler.scheduleOnce(10 seconds, self, Publish)
      } recoverWith {
        case t: Throwable =>
          client ! t.getMessage
          listeners foreach { _ ! t.getMessage }
          context.system.scheduler.scheduleOnce(10 seconds, self, Publish)
          Future failed t
      }
  }
}

case object AuthorizeMercury

case object Authorized

case object Publish