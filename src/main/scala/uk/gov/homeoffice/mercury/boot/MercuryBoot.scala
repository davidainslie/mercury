package uk.gov.homeoffice.mercury.boot

import scala.language.postfixOps
import akka.actor.ActorSystem
import uk.gov.homeoffice.configuration.HasConfig
import uk.gov.homeoffice.mercury.MercuryActor
import uk.gov.homeoffice.mercury.boot.configuration.{HocsCredentials, HocsWebService, S3}

trait MercuryBoot extends HasConfig {
  this: App =>

  val system = ActorSystem("mercury-actor-system", config)
  sys addShutdownHook system.terminate()

  system.actorOf(MercuryActor.props(S3(), HocsCredentials(), HocsWebService()), name = "mercury-actor")
}