package com.gu.fastly

import com.amazonaws.services.cloudwatch.AmazonCloudWatchClientBuilder
import com.amazonaws.services.cloudwatch.model.{ Dimension, MetricDatum, PutMetricDataRequest, StandardUnit }
import com.amazonaws.services.kinesis.clientlibrary.types.UserRecord
import com.amazonaws.services.kinesis.model.Record
import com.amazonaws.services.lambda.runtime.events.KinesisEvent
import com.amazonaws.services.sns.AmazonSNSClientBuilder
import com.amazonaws.services.sns.model.PublishRequest
import com.gu.contentapi.client.model.v1.ContentType
import com.gu.crier.model.event.v1._
import com.gu.googleamp.AmpFlusher
import okhttp3._
import org.apache.commons.codec.digest.DigestUtils

import scala.collection.JavaConverters._

class Lambda {

  private val config = Config.load()
  private val httpClient = new OkHttpClient()

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
        case (ItemType.Content, EventType.Delete) => {
          val dotcomCanonicalPurge = sendFastlyPurgeRequestAndAmpPingRequest(event.payloadId, Hard, config.fastlyDotcomServiceId, makeDotcomSurrogateKey(event.payloadId), config.fastlyDotcomApiKey)
          // Why is there no MAPI purge here?
          val dotcomAliasPurges = extractAliasPaths(event).fold(Seq.empty[Boolean])({
            _.map { aliasPath =>
              sendFastlyPurgeRequestAndAmpPingRequest(aliasPath, Hard, config.fastlyDotcomServiceId, makeDotcomSurrogateKey(aliasPath), config.fastlyDotcomApiKey)
            }
          })
          dotcomCanonicalPurge && !dotcomAliasPurges.contains(false)
        }

        case (ItemType.Content, EventType.Update) =>
          val contentType = extractUpdateContentType(event)
          val dotcomCanonicalPurge = sendFastlyPurgeRequest(event.payloadId, Soft, config.fastlyDotcomServiceId, makeDotcomSurrogateKey(event.payloadId), config.fastlyDotcomApiKey, contentType)
          val jsonPurge = sendFastlyPurgeRequestForAjaxFile(event.payloadId, contentType)
          val mapiCanonicalPurge = sendFastlyPurgeRequest(event.payloadId, Soft, config.fastlyMapiServiceId, makeMapiSurrogateKey(event.payloadId), config.fastlyMapiApiKey, contentType)
          val aliasPurges = extractAliasPaths(event).fold(Seq.empty[Boolean])({
            _.map { aliasPath =>
              val dotcomAliasPurge = sendFastlyPurgeRequest(aliasPath, Soft, config.fastlyDotcomServiceId, makeDotcomSurrogateKey(aliasPath), config.fastlyDotcomApiKey, contentType)
              val jsonAliasPurge = sendFastlyPurgeRequestForAjaxFile(aliasPath, contentType)
              val mapiAliasPurge = sendFastlyPurgeRequest(aliasPath, Soft, config.fastlyMapiServiceId, makeMapiSurrogateKey(aliasPath), config.fastlyMapiApiKey, contentType)
              dotcomAliasPurge && jsonAliasPurge && mapiAliasPurge
            }
          })
          dotcomCanonicalPurge && jsonPurge && mapiCanonicalPurge && !aliasPurges.contains(false)

        case (ItemType.Content, EventType.RetrievableUpdate) =>
          val contentType = extractUpdateContentType(event)
          val dotcomCanonicalPurge = sendFastlyPurgeRequest(event.payloadId, Soft, config.fastlyDotcomServiceId, makeDotcomSurrogateKey(event.payloadId), config.fastlyDotcomApiKey, contentType)
          val jsonCanonicalPurge = sendFastlyPurgeRequestForAjaxFile(event.payloadId, contentType)
          val mapiCanonicalPurge = sendFastlyPurgeRequest(event.payloadId, Soft, config.fastlyMapiServiceId, makeMapiSurrogateKey(event.payloadId), config.fastlyMapiApiKey, contentType)
          val aliasPurges = extractAliasPaths(event).fold(Seq.empty[Boolean])({
            _.map { aliasPath =>
              val dotcomAliasPurge = sendFastlyPurgeRequest(aliasPath, Soft, config.fastlyDotcomServiceId, makeDotcomSurrogateKey(aliasPath), config.fastlyDotcomApiKey, contentType)
              val jsonAliasPurge = sendFastlyPurgeRequestForAjaxFile(aliasPath, contentType)
              val mapiAliasPurge = sendFastlyPurgeRequest(aliasPath, Soft, config.fastlyMapiServiceId, makeMapiSurrogateKey(aliasPath), config.fastlyMapiApiKey, contentType)
              dotcomAliasPurge && jsonAliasPurge && mapiAliasPurge
            }
          })
          dotcomCanonicalPurge && jsonCanonicalPurge && mapiCanonicalPurge && !aliasPurges.contains(false)

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

      val contentType = extractUpdateContentType(event)

      supportedDecacheEventType.map { decacheEventType =>
        val contentDecachedEvent = com.gu.fastly.model.event.v1.ContentDecachedEvent(
          contentId = event.payloadId,
          eventType = decacheEventType,
          contentType = contentType
        )
        try {
          val publishRequest = new PublishRequest()
          publishRequest.setTopicArn(config.decachedContentTopic)
          publishRequest.setMessage(ContentDecachedEventSerializer.serialize(contentDecachedEvent))
          snsClient.publish(publishRequest)
        } catch {
          case t: Throwable =>
            println("Warning; publish sns decached event failed: ${t.getMessage}")
        }
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
      AmpFlusher.sendAmpDeleteRequest(contentId)
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

  private def extractAliasPaths(event: Event): Option[Seq[String]] = {
    event.payload.flatMap { payload =>
      payload match {
        case EventPayload.DeletedContent(deleted) => deleted.aliasPaths
        case EventPayload.Content(content) => content.aliasPaths
        case EventPayload.RetrievableContent(retrievable) => retrievable.aliasPaths
        case _ => None
      }
    }
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

}
