package com.gu.fastly

import com.amazonaws.services.cloudwatch.AmazonCloudWatchClientBuilder
import com.amazonaws.services.cloudwatch.model.{Dimension, MetricDatum, PutMetricDataRequest, StandardUnit}
import com.amazonaws.services.kinesis.clientlibrary.types.UserRecord
import com.amazonaws.services.kinesis.model.Record
import com.amazonaws.services.lambda.runtime.events.KinesisEvent
import com.gu.contentapi.client.model.v1.ContentType
import com.gu.crier.model.event.v1._
import io.circe.generic.auto._
import io.circe.parser._
import okhttp3._
import org.apache.commons.codec.digest.DigestUtils

import java.io.IOException
import scala.collection.JavaConverters._

class Lambda {

  private val config = Config.load()
  private val httpClient = new OkHttpClient()
  private val cloudWatchClient = AmazonCloudWatchClientBuilder.defaultClient

  def handle(event: KinesisEvent) {
    val rawRecords: List[Record] = event.getRecords.asScala.map(_.getKinesis).toList
    val userRecords = UserRecord.deaggregate(rawRecords.asJava)

    println(s"Processing ${userRecords.size} records ...")
    val events = CrierEventDeserializer.deserializeEvents(userRecords.asScala)

    val distinctEvents = UpdateDeduplicator.filterAndDeduplicateContentEvents(events)
    println(s"Processing ${distinctEvents.size} distinct content events from batch of ${events.size} events...")

    CrierEventProcessor.process(distinctEvents) { event =>
      (event.itemType, event.eventType) match {
        case (ItemType.Content, EventType.Delete) =>
          sendFastlyPurgeRequestAndAmpPingRequest(event.payloadId, Hard, config.fastlyDotcomServiceId, makeDotcomSurrogateKey(event.payloadId), config.fastlyDotcomApiKey)

        case (ItemType.Content, EventType.Update) =>
          val contentType = extractUpdateContentType(event)
          sendFastlyPurgeRequest(event.payloadId, Soft, config.fastlyDotcomServiceId, makeDotcomSurrogateKey(event.payloadId), config.fastlyDotcomApiKey, contentType)
          sendFastlyPurgeRequestForAjaxFile(event.payloadId, contentType)
          sendFastlyPurgeRequest(event.payloadId, Soft, config.fastlyMapiServiceId, makeMapiSurrogateKey(event.payloadId), config.fastlyMapiApiKey, contentType)
        //sendFacebookNewstabPing(event.payloadId)

        case (ItemType.Content, EventType.RetrievableUpdate) =>
          val contentType = extractUpdateContentType(event)
          sendFastlyPurgeRequest(event.payloadId, Soft, config.fastlyDotcomServiceId, makeDotcomSurrogateKey(event.payloadId), config.fastlyDotcomApiKey, contentType)
          sendFastlyPurgeRequestForAjaxFile(event.payloadId, contentType)
          sendFastlyPurgeRequest(event.payloadId, Soft, config.fastlyMapiServiceId, makeMapiSurrogateKey(event.payloadId), config.fastlyMapiApiKey, contentType)
        //sendFacebookNewstabPing(event.payloadId)

        case other =>
          // for now we only send purges for content, so ignore any other events
          false
      }
    }

    println(s"Finished.")
  }

  // OkHttp requires a media type even for an empty POST body
  private val EmptyJsonBody: RequestBody =
    RequestBody.create(MediaType.parse("application/json; charset=utf-8"), "")

  private sealed trait PurgeType
  private object Soft extends PurgeType { override def toString = "soft" }
  private object Hard extends PurgeType { override def toString = "hard" }

  def makeMapiSurrogateKey(contentId: String): String = s"Item/$contentId"

  def makeDotcomSurrogateKey(contentId: String): String = {
    val contentPath = s"/$contentId"
    val dotcomSurrogateKey = DigestUtils.md5Hex(contentPath)
    dotcomSurrogateKey
  }

  private def sendFastlyPurgeRequestAndAmpPingRequest(contentId: String, purgeType: PurgeType, serviceId: String, surrogateKey: String, fastlyApiKey: String): Boolean = {
    if (sendFastlyPurgeRequest(contentId, purgeType, serviceId, surrogateKey, fastlyApiKey))
      sendAmpPingRequest(contentId)
    else
      false
  }

  private def sendFastlyPurgeRequestForAjaxFile(contentId: String, contentType: Option[ContentType]) = {
    sendFastlyPurgeRequest(s"${contentId}.json", Soft, config.fastlyApiNextgenServiceId, makeDotcomSurrogateKey(s"${contentId}.json"), config.fastlyDotcomApiKey, contentType)
  }

  /**
   * Send a hard purge request to Fastly API.
   *
   * @return whether a piece of content was purged or not
   */
  def sendFastlyPurgeRequest(contentId: String, purgeType: PurgeType, serviceId: String, surrogateKey: String, fastlyApiKey: String, contentType: Option[ContentType] = None): Boolean = {
    val url = s"https://api.fastly.com/service/$serviceId/purge/$surrogateKey"

    val requestBuilder = new Request.Builder()
      .url(url)
      .header("Fastly-Key", fastlyApiKey)
      .post(EmptyJsonBody)

    val request = (purgeType match {
      case Soft => requestBuilder.header("Fastly-Soft-Purge", "1")
      case _ => requestBuilder
    }).build()

    val response = httpClient.newCall(request).execute()
    println(s"Sent $purgeType purge request for content with ID [$contentId], service with ID [$serviceId] and surrogate key [$surrogateKey]. Response from Fastly API: [${response.code}] [${response.body.string}]")

    val purged = response.code == 200

    sendPurgeCountMetric(contentType)

    purged
  }
  /**
   * Send a ping request to Google AMP to refresh the cache.
   * See https://developers.google.com/amp/cache/update-ping
   *
   * @return whether the request was successfully processed by the server
   */
  private def sendAmpPingRequest(contentId: String): Boolean = {
    val contentPath = s"/$contentId"

    val url = s"https://amp-theguardian-com.cdn.ampproject.org/update-ping/c/s/amp.theguardian.com${contentPath}"

    val request = new Request.Builder()
      .url(url)
      .get()
      .build()

    val response = httpClient.newCall(request).execute()
    println(s"Sent ping request for content with ID [$contentId]. Response from Google AMP CDN: [${response.code}] [${response.body.string}]")

    response.code == 204
  }

  private def extractUpdateContentType(event: Event): Option[ContentType] = {
    // An Update event should contain a Content payload with a content type.
    // A RetrievableContent event contains a content type hint and a link to the full content
    event.payload.flatMap { payload =>
      payload match {
        case EventPayload.Content(content) => Some(content.`type`)
        case EventPayload.RetrievableContent(retrievableContent) => retrievableContent.contentType
        case _ => None
      }
    }
  }

  // Count the number of purge requests we are making
  private def sendPurgeCountMetric(contentType: Option[ContentType]): Unit = {
    val contentTypeDimension = contentType.map { ct =>
      new Dimension()
        .withName("contentType")
        .withValue(ct.name);
    }

    val dimensions = Seq(contentTypeDimension).flatten

    val metric = new MetricDatum()
      .withMetricName("purges")
      .withUnit(StandardUnit.None)
      .withDimensions(dimensions.asJavaCollection)
      .withValue(1)

    val putMetricDataRequest = new PutMetricDataRequest().
      withNamespace("fastly-cache-purger").
      withMetricData(metric)

    try {
      cloudWatchClient.putMetricData(putMetricDataRequest)
    } catch {
      case t: Throwable =>
        println("Warning; cloudwatch metrics ping failed: " + t.getMessage)
    }
  }

  /**
   * Identify if the content update was an article.
   * Additional third parties may be interested in these in the near future
   */
  /**
   * If this content update is editorially interesting to Facebook Newstab ping their update end point.
   *
   * @return decision and/or ping completed successfully
   */
  def sendFacebookNewstabPing(contentId: String): Boolean = {
    val contentPath = s"/$contentId"
    val contentWebUrl = s"https://www.theguardian.com${contentPath}"

    // This is an interesting question which will almost certainly by iterated on.
    // Basing this decision entirely on the contentId is unlikely age well.
    // Our opening move for the proof of concept is to dibble a small amount of content which is unlikely to be taken down.
    // Travel articles sound safe.
    val contentIsInterestingToFacebookNewstab = contentId.contains("travel/2020")

    if (contentIsInterestingToFacebookNewstab) {
      val scope = config.facebookNewsTabScope

      try {
        // The POST endpoint with URL encoded parameters as per New Tab documentation
        val indexArticle = new HttpUrl.Builder()
          .scheme("https")
          .host("graph.facebook.com")
          .addQueryParameter("id", contentWebUrl)
          .addQueryParameter("scopes", scope)
          .addQueryParameter("access_token", config.facebookNewsTabAccessToken)
          .addQueryParameter("scrape", "true")
          .build()

        val request = new Request.Builder()
          .url(indexArticle)
          .post(EmptyJsonBody)
          .build()

        val response = httpClient.newCall(request).execute()

        // Soft evaluate the Facebook response
        // Their documentation does not specifically mention response codes.
        // Lets evaluate and log our interpretation of the response for now
        val wasSuccessful = response.code match {
          case 200 =>
            decode[FacebookNewstabResponse](response.body.string()).fold({ error =>
              println("Failed to parse Facebook Newstab response: " + error.getMessage)
              false
            }, { facebookResponse =>
              facebookResponse.scopes.get(scope).contains("INDEXED")
            })
          case _ =>
            println("Received unexpected response code from Facebook: " + _)
            false
        }

        println(s"Sent Facebook Newstab ping request for content with url [$contentWebUrl]. " +
          s"Response from Facebook: [${response.code}] [${response.body.string}]. " +
          s"Was successful: [$wasSuccessful]")

      } catch {
        case e: IOException =>
          println("Facebook call threw IOException; this could indicate a timeout: " + e.getMessage)
          false
        case e: Throwable =>
          println("Facebook call threw unexpected Exception: " + e.getMessage)
          false
      }

      true // Always return true during the proof on concept until we are confident about Facebook's responses

    } else {
      true
    }
  }

  case class FacebookNewstabResponse(url: String, scopes: Map[String, String])

}
