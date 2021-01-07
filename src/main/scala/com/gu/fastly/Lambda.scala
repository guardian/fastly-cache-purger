package com.gu.fastly

import com.amazonaws.services.cloudwatch.AmazonCloudWatchClientBuilder
import com.amazonaws.services.cloudwatch.model.{Dimension, MetricDatum, PutMetricDataRequest, StandardUnit}
import com.amazonaws.services.kinesis.clientlibrary.types.UserRecord
import com.amazonaws.services.kinesis.model.Record
import com.amazonaws.services.lambda.runtime.events.KinesisEvent
import com.amazonaws.services.sns.AmazonSNSClientBuilder
import com.amazonaws.services.sns.model.PublishRequest
import com.gu.contentapi.client.model.v1.ContentType
import com.gu.crier.model.event.v1._
import io.circe.generic.auto._
import io.circe.parser._
import okhttp3._
import org.apache.commons.codec.digest.DigestUtils

import java.io.IOException
import java.util.concurrent.TimeUnit
import scala.collection.JavaConverters._

class Lambda {

  private val config = Config.load()
  private val httpClient = new OkHttpClient()
  private val facebookHttpClient = {
    new OkHttpClient.Builder()
      .connectTimeout(5000, TimeUnit.MILLISECONDS)
      .readTimeout(5000, TimeUnit.MILLISECONDS)
      .build()
  }

  private val cloudWatchClient = AmazonCloudWatchClientBuilder.defaultClient
  private val snsClient = AmazonSNSClientBuilder.defaultClient

  def handle(event: KinesisEvent) {
    val rawRecords: List[Record] = event.getRecords.asScala.map(_.getKinesis).toList
    val userRecords = UserRecord.deaggregate(rawRecords.asJava)

    println(s"Processing ${userRecords.size} records ...")
    val events = CrierEventDeserializer.deserializeEvents(userRecords.asScala)

    val distinctContentEvents = UpdateDeduplicator.filterAndDeduplicateContentEvents(events)
    println(s"Processing ${distinctContentEvents.size} distinct content events from batch of ${events.size} events...")

    val successfulPurges = CrierEventProcessor.process(distinctContentEvents) { event =>
      (event.itemType, event.eventType) match {
        case (ItemType.Content, EventType.Delete) =>
          sendFastlyPurgeRequestAndAmpPingRequest(event.payloadId, Hard, config.fastlyDotcomServiceId, makeDotcomSurrogateKey(event.payloadId), config.fastlyDotcomApiKey)

        case (ItemType.Content, EventType.Update) =>
          val contentType = extractUpdateContentType(event)
          sendFastlyPurgeRequest(event.payloadId, Soft, config.fastlyDotcomServiceId, makeDotcomSurrogateKey(event.payloadId), config.fastlyDotcomApiKey, contentType)
          sendFastlyPurgeRequestForAjaxFile(event.payloadId, contentType)
          sendFastlyPurgeRequest(event.payloadId, Soft, config.fastlyMapiServiceId, makeMapiSurrogateKey(event.payloadId), config.fastlyMapiApiKey, contentType)

        case (ItemType.Content, EventType.RetrievableUpdate) =>
          val contentType = extractUpdateContentType(event)
          sendFastlyPurgeRequest(event.payloadId, Soft, config.fastlyDotcomServiceId, makeDotcomSurrogateKey(event.payloadId), config.fastlyDotcomApiKey, contentType)
          sendFastlyPurgeRequestForAjaxFile(event.payloadId, contentType)
          sendFastlyPurgeRequest(event.payloadId, Soft, config.fastlyMapiServiceId, makeMapiSurrogateKey(event.payloadId), config.fastlyMapiApiKey, contentType)

        case other =>
          // for now we only send purges for content, so ignore any other events
          false
      }
    }

    // Republish events for successful deletes and updates as
    // com.gu.crier.model.event.v1.Event events thrift serialized and base64 encoded
    successfulPurges.foreach { event =>
      val supportedDecacheEventType = event.eventType match {
        case EventType.Update => Some(com.gu.fastly.model.event.v1.EventType.Update)
        case EventType.Delete => Some(com.gu.fastly.model.event.v1.EventType.Delete)
        case _ => None
      }

      supportedDecacheEventType.map { decacheEventType =>
        val contentDecachedEvent = com.gu.fastly.model.event.v1.ContentDecachedEvent(
          contentId = event.payloadId,
          eventType = decacheEventType
        )
        try {
          val message = ContentDecachedEventSerializer.serialize(contentDecachedEvent)
          println("Publishing SNS decached message for content id '" + event.payloadId + "' of length: " + message.length)

          val publishRequest = new PublishRequest()
          publishRequest.setTopicArn(config.decachedContentTopic)
          publishRequest.setMessage(message)
          snsClient.publish(publishRequest)
        } catch {
          case t: Throwable =>
            println("Warning; publish sns decached event failed: " + t.getMessage)
        }
      }
    }

    val maximumFacebookPingsPerBatch = 3

    // Send Facebook denylist pings for removed content
    val deleteEvents = successfulPurges.filter(event =>
      (event.itemType, event.eventType) match {
        case (ItemType.Content, EventType.Delete) => true
        case _ => false
      })

    val facebookNewstabDeletes = deleteEvents
      .filter(event => contentIsInterestingToFacebookNewstab(event.payloadId))
      .take(maximumFacebookPingsPerBatch) // Limit the volume of pings during proof of concept

    if (facebookNewstabDeletes.nonEmpty) {
      println("Sending Facebook denylist pings for " + facebookNewstabDeletes.size + " content ids")
      facebookNewstabDeletes.map { event =>
        sendFacebookNewsitemDenylistRequest(event.payloadId)
      }
    }

    // Send Facebook Newstab pings for relevant content updates
    // Filter for update events which are of interest to Facebook
    val updateEvents = successfulPurges.filter { event =>
      (event.itemType, event.eventType) match {
        case (ItemType.Content, EventType.Update) => true
        case (ItemType.Content, EventType.RetrievableUpdate) => true
        case _ => false
      }
    }
    val facebookNewstabUpdates = updateEvents
      .filter(event => contentIsInterestingToFacebookNewstab(event.payloadId))
      .take(maximumFacebookPingsPerBatch) // Limit the volume of pings during proof of concept

    if (facebookNewstabUpdates.nonEmpty) {
      println("Sending Facebook pings for " + facebookNewstabUpdates.size + " content ids")
      facebookNewstabUpdates.map { event =>
        sendFacebookNewstabPing(event.payloadId)
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

  // This is an interesting question which will almost certainly be iterated on.
  // Basing this decision entirely on the contentId is unlikely to age well.
  // Our opening move for the proof of concept is to dibble a small amount of content which is unlikely to be taken down.
  // Travel articles sound safe.
  def contentIsInterestingToFacebookNewstab(contentId: String) = contentId.contains("travel/2020")

  /**
   * If this content update is editorially interesting to Facebook Newstab ping their update end point.
   *
   * @return decision and/or ping completed successfully
   */
  def sendFacebookNewstabPing(contentId: String): Boolean = {
    println("Sending Facebook ping for content id: " + contentId)
    val contentWebUrl = webUrlFor(contentId)
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

      val response = facebookHttpClient.newCall(request).execute()

      // Soft evaluate the Facebook response
      // Their documentation does not specifically mention response codes.
      // Lets evaluate and log our interpretation of the response for now
      val responseBody = response.body.string()
      val wasSuccessful = isExpectedFacebookNewstabResponse(response, responseBody, scope, "INDEXED")

      println(s"Sent Facebook Newstab ping request for content with url [$contentWebUrl]. " +
        s"Response from Facebook: [${response.code}] [${responseBody}]. " +
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
  }

  /**
   * Denylist this content within Facebook News Tab
   * This will add the article to the News Tab denylist hiding the article from the News Tab.
   * "Denylisting only affects articles in the News Tab. Articles will still be visible on other Facebook surfaces."
   *
   * @return decision and/or denylist ping completed successfully
   */
  def sendFacebookNewsitemDenylistRequest(contentId: String): Boolean = {
    val contentWebUrl = webUrlFor(contentId)
    val scope = config.facebookNewsTabScope

    //POST endpoint with URL encoded parameters as per New Tab documentation
    val indexArticle = new HttpUrl.Builder()
      .scheme("https")
      .host("graph.facebook.com")
      .addQueryParameter("id", contentWebUrl)
      .addQueryParameter("scopes", scope)
      .addQueryParameter("access_token", config.facebookNewsTabAccessToken)
      .addQueryParameter("denylist", "true")
      .build();

    val request = new Request.Builder()
      .url(indexArticle)
      .post(EmptyJsonBody)
      .build()

    val response = facebookHttpClient.newCall(request).execute()

    // Soft evaluate the Facebook response
    // Their documentation does not specifically mention response codes.
    // Lets evaluate and log our interpretation of the response for now
    val responseBody = response.body.string()
    val wasSuccessful = isExpectedFacebookNewstabResponse(response, responseBody, scope, "DENYLISTED")

    println(s"Sent Facebook Newstab denylist request for content with url [$contentWebUrl]. " +
      s"Response from Facebook: [${response.code}] [${responseBody}]. " +
      s"Was successful: [$wasSuccessful]")

    true // Always return true during the proof on concept until we are confident about Facebook's responses
  }

  private def webUrlFor(contentId: String) = {
    val contentPath = s"/$contentId"
    s"https://www.theguardian.com${contentPath}"
  }

  private def isExpectedFacebookNewstabResponse(response: Response, responseBody: String, scope: String, expected: String): Boolean = {
    response.code match {
      case 200 =>
        decode[FacebookNewstabResponse](responseBody).fold({ error =>
          println("Failed to parse Facebook Newstab response: " + error.getMessage)
          false
        }, { facebookResponse =>
          facebookResponse.scopes.get(scope).contains(expected)
        })
      case _ =>
        println("Received unexpected response code from Facebook: " + _)
        false
    }
  }

  case class FacebookNewstabResponse(url: String, scopes: Map[String, String])

}
