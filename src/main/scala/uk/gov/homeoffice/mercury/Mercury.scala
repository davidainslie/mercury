package uk.gov.homeoffice.mercury

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import akka.stream.scaladsl.{Source, StreamConverters}
import play.api.http.Status._
import play.api.mvc.MultipartFormData.{DataPart, FilePart}
import grizzled.slf4j.Logging
import uk.gov.homeoffice.aws.s3._
import uk.gov.homeoffice.web.WebService

object Mercury {
  val authorizationEndpoint = "/alfresco/s/api/login"

  val publicationEndpoint = "/alfresco/s/homeoffice/cts/autoCreateDocument"

  def authorize(credentials: Credentials)(implicit webService: WebService): Future[WebService with Authorization] = {
    webService endpoint authorizationEndpoint post credentials flatMap { response =>
      response.status match {
        case OK => Future successful new WebService(webService.host, webService.wsClient) with Authorization {
          override val token: String = (response.json \ "data" \ "ticket").as[String]
        }

        case _ => Future failed new Exception(s"""Failed to authorize against "${webService.host}" because of: Http response status ${response.status}, ${response.statusText}""")
      }
    }
  }

  def apply(s3: S3, webService: WebService with Authorization) = new Mercury(s3, webService)
}

class Mercury(val s3: S3, val webService: WebService with Authorization) extends Logging {
  import Mercury._

  lazy val authorizationParam = "alf_ticket" -> webService.token

  def publish: Future[Seq[Publication]] = {
    info("Publishing")

    s3.pullResources.flatMap { pulledResources =>
      val publications = pulledResources.toSeq.map { case (resourcesKey, resources) =>
        val fileParts = resources map {
          case Resource(key, inputStream, contentType, _) =>
            val data = StreamConverters.fromInputStream(() => inputStream)
            val fileName = key.substring(key.lastIndexOf("/") + 1)
            FilePart("file", fileName, Some(contentType), data) // TODO Key? Name of file? The type?
        }

        val numberOfFileParts = if (resources.size == 1) "1 resource" else s"${resources.size} resources"
        info(s"""Publishing to endpoint ${webService.host}$publicationEndpoint, $numberOfFileParts associated with S3 key $resourcesKey""")

        webService endpoint publicationEndpoint withQueryString authorizationParam post Source(List(DataPart("caseType", "IMCB"), DataPart("name", "email.txt")) ++ fileParts) map { response =>
          response.status match {
            case OK =>
              // TODO delete "folder"
              Publication("caseRef") // TODO

            case _ =>
              throw new Exception(s"""Failed to publish to "${webService.host}" because of: Http response status ${response.status}, ${response.statusText}""")
          }
        }
      }

      Future sequence publications
    }
  }
}