package com.gu.fastly

import java.io.IOException

import com.amazonaws.services.kinesis.model.Record
import com.amazonaws.services.lambda.runtime.{ Context, RequestHandler }
import com.amazonaws.services.lambda.runtime.events.KinesisEvent
import com.gu.crier.model.event.v1.{ Event, EventPayload, EventType }
import com.gu.fastly.CrierEventProcessor.{ decodeRecord, successfulEvents }
import okhttp3._
import org.apache.commons.codec.digest.DigestUtils

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ Future, Promise, Await, duration }

class Lambda extends RequestHandler[KinesisEvent, Unit] {

  private val config = Config.load()
  private val httpClient = new OkHttpClient()

  def handleRequest(event: KinesisEvent, context: Context): Unit = {
    Await.result(processEvent(event), duration.Duration(context.getRemainingTimeInMillis, duration.MILLISECONDS))
    println(s"Finished.")
  }

  def processEvent(event: KinesisEvent): Future[Unit] = {
    val rawRecords: List[Record] = event.getRecords.asScala.map(_.getKinesis).toList

    Future.traverse(
      rawRecords.map(decodeRecord) collect (successfulEvents andThen decidePurgeType)
    ) {
        case DeleteEvent(contentId) => hardPurge(contentId)
        case UpdateEvent(contentId) => softPurge(contentId)
      }.flatMap { results: List[List[PurgeResult]] =>
        log(results.flatten)
        val idsForFullySuccessfulPurge: List[ContentId] = results.collect({
          case resSet: List[PurgeResult] if resSet.forall(_.status) => resSet.head.contentId
        })
        publish(idsForFullySuccessfulPurge)
      }
  }

  type ContentId = String

  sealed trait Service

  object AMP extends Service { override def toString = "AMP" }
  object Dotcom extends Service { override def toString = "Dotcom" }
  object Nextgen extends Service { override def toString = "Nextgen" }
  object Mapi extends Service { override def toString = "Mapi" }

  sealed trait CAPIEventType
  case class UpdateEvent(contentId: ContentId) extends CAPIEventType
  case class DeleteEvent(contentId: ContentId) extends CAPIEventType

  case class PurgeResult(contentId: ContentId, status: Boolean, service: Service)

  private sealed trait PurgeType
  private object Soft extends PurgeType { override def toString = "soft" }
  private object Hard extends PurgeType { override def toString = "hard" }

  // OkHttp requires a media type even for an empty POST body
  private val EmptyJsonBody: RequestBody =
    RequestBody.create(MediaType.parse("application/json; charset=utf-8"), "")

  def makeMapiSurrogateKey(contentId: String): String = s"Item/$contentId"

  def makeDotcomSurrogateKey(contentId: String): String = {
    val contentPath = s"/$contentId"
    val dotcomSurrogateKey = DigestUtils.md5Hex(contentPath)
    dotcomSurrogateKey
  }

  def decidePurgeType: PartialFunction[Event, CAPIEventType] = {
    case Event(_, EventType.Update, _, _, Some(EventPayload.Content(content))) => UpdateEvent(content.id)
    case Event(_, EventType.RetrievableUpdate, _, _, Some(EventPayload.RetrievableContent(content))) => UpdateEvent(content.id)
    case Event(_, EventType.Delete, _, _, Some(EventPayload.RetrievableContent(content))) => DeleteEvent(content.id)
  }

  def hardPurge(contentId: ContentId): Future[List[PurgeResult]] = {
    for {
      dotcomIsPurged <- sendFastlyPurgeRequest(contentId, Hard, config.fastlyDotcomServiceId, makeDotcomSurrogateKey(contentId), config.fastlyDotcomApiKey)
      ampIsPurged <- if (dotcomIsPurged) sendAmpPingRequest(contentId) else Future.successful(false)
    } yield List(PurgeResult(contentId, dotcomIsPurged, Dotcom), PurgeResult(contentId, ampIsPurged, AMP))
  }

  //See https://docs.fastly.com/en/guides/soft-purges
  def softPurge(contentId: ContentId): Future[List[PurgeResult]] = {
    val dotcomResult = sendFastlyPurgeRequest(contentId, Soft, config.fastlyDotcomServiceId, makeDotcomSurrogateKey(contentId), config.fastlyDotcomApiKey).map(PurgeResult(contentId, _, Dotcom))
    val nextgenResult = sendFastlyPurgeRequest(contentId, Soft, config.fastlyApiNextgenServiceId, makeDotcomSurrogateKey(contentId), config.fastlyDotcomApiKey).map(PurgeResult(contentId, _, Nextgen))
    val mapiResult = sendFastlyPurgeRequest(contentId, Soft, config.fastlyMapiServiceId, makeMapiSurrogateKey(contentId), config.fastlyMapiApiKey).map(PurgeResult(contentId, _, Mapi))

    Future.sequence(List(dotcomResult, nextgenResult, mapiResult))
  }

  def log(results: List[PurgeResult]): Unit = {
    val purgeCountsByServiceAndStatus = results.groupBy(res => (res.service, res.status)).mapValues(_.size)
    purgeCountsByServiceAndStatus.foreach {
      case ((service, true), idCount) => println(s"Successfully purged $service for $idCount content ids")
      case ((service, false), idCount) => println(s"Failed to purge $service for $idCount content ids")
    }
  }

  def publish(contentIds: List[ContentId]): Future[Unit] = {
    println(s"Writing ${contentIds.size} content ids to SQS for Twitter cache clearing")
    Future.successful(Unit)
  }

  /**
   * Send a hard purge request to Fastly API.
   *
   * @return whether a piece of content was purged or not
   */
  def sendFastlyPurgeRequest(contentId: String, purgeType: PurgeType, serviceId: String, surrogateKey: String, fastlyApiKey: String): Future[Boolean] = {
    val url = s"https://api.fastly.com/service/$serviceId/purge/$surrogateKey"

    val requestBuilder = new Request.Builder()
      .url(url)
      .header("Fastly-Key", fastlyApiKey)
      .post(EmptyJsonBody)

    val request = (purgeType match {
      case Soft => requestBuilder.header("Fastly-Soft-Purge", "1")
      case _ => requestBuilder
    }).build()

    val promise = Promise[Boolean]()
    httpClient.newCall(request).enqueue(new Callback {
      override def onFailure(call: Call, e: IOException) = {
        println(s"Failed to send $purgeType purge request for content with ID [$contentId], service with ID [$serviceId] and surrogate key [$surrogateKey]. Okhttp request exception: $e")
        promise.failure(e)
      }
      override def onResponse(call: Call, resp: Response) = {
        println(s"Sent $purgeType purge request for content with ID [$contentId], service with ID [$serviceId] and surrogate key [$surrogateKey]. Response from Fastly API: [${resp.code}] [${resp.body.string}]")
        promise.success(resp.code == 200)
      }
    })
    promise.future
  }

  /**
   * Send a ping request to Google AMP to refresh the cache.
   * See https://developers.google.com/amp/cache/update-ping
   *
   * @return whether the request was successfully processed by the server
   */
  private def sendAmpPingRequest(contentId: String): Future[Boolean] = {
    val contentPath = s"/$contentId"

    val url = s"https://amp-theguardian-com.cdn.ampproject.org/update-ping/c/s/amp.theguardian.com${contentPath}"

    val request = new Request.Builder()
      .url(url)
      .get()
      .build()

    val promise = Promise[Boolean]()

    httpClient.newCall(request).enqueue(new Callback {
      override def onFailure(call: Call, e: IOException) = {
        println(s"Failed to send AMP ping request for content with ID [$contentId]. Okhttp request exception: $e")
        promise.failure(e)
      }
      override def onResponse(call: Call, resp: Response) = {
        println(s"Sent ping request for content with ID [$contentId]. Response from Google AMP CDN: [${resp.code}] [${resp.body.string}]")
        promise.success(resp.code == 204)
      }
    })
    promise.future
  }
}
