package com.gu.fastly

import java.util.concurrent.TimeUnit.MILLISECONDS

import com.amazonaws.services.kinesis.clientlibrary.types.UserRecord
import com.amazonaws.services.kinesis.model.Record
import com.amazonaws.services.kinesis.producer.{ KinesisProducer, UserRecordResult }
import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.events.KinesisEvent
import com.gu.crier.model.event.v1._
import okhttp3._
import org.apache.commons.codec.digest.DigestUtils

import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.concurrent.duration.Duration

class Lambda {

  private val config = Config.load()
  private val httpClient = new OkHttpClient()

  val kinesisProducer = new KinesisProducer()

  def handle(event: KinesisEvent, context: Context): Unit = {
    val rawRecords: List[Record] = event.getRecords.asScala.map(_.getKinesis).toList
    val userRecords = UserRecord.deaggregate(rawRecords.asJava).asScala

    println(s"Processing ${userRecords.size} records ...")

    CrierEventProcessor.process(userRecords) { event =>
      (event.itemType, event.eventType) match {
        case (ItemType.Content, EventType.Delete) =>
          sendFastlyPurgeRequestAndAmpPingRequest(event.payloadId, Hard, config.fastlyDotcomServiceId, makeDotcomSurrogateKey(event.payloadId), config.fastlyDotcomApiKey)
        case (ItemType.Content, EventType.Update) =>
          sendFastlyPurgeRequest(event.payloadId, Soft, config.fastlyDotcomServiceId, makeDotcomSurrogateKey(event.payloadId), config.fastlyDotcomApiKey)
          sendFastlyPurgeRequest(s"${event.payloadId}.json", Soft, config.fastlyApiNextgenServiceId, makeDotcomSurrogateKey(s"${event.payloadId}.json"), config.fastlyDotcomApiKey)
          sendFastlyPurgeRequest(s"${event.payloadId}.json", Soft, config.fastlyMapiServiceId, makeMapiSurrogateKey(s"${event.payloadId}.json"), config.fastlyMapiApiKey)
        case (ItemType.Content, EventType.RetrievableUpdate) =>
          sendFastlyPurgeRequest(event.payloadId, Soft, config.fastlyDotcomServiceId, makeDotcomSurrogateKey(event.payloadId), config.fastlyDotcomApiKey)
          sendFastlyPurgeRequest(event.payloadId, Soft, config.fastlyMapiServiceId, makeMapiSurrogateKey(event.payloadId), config.fastlyMapiApiKey)

        case other =>
          // for now we only send purges for content, so ignore any other events
          false
      }
    }

    val recordsAddedResult: Seq[UserRecordResult] = Await.result(
      PurgedEventWriter.addRecords(rawRecords, kinesisProducer), Duration(context.getRemainingTimeInMillis, MILLISECONDS)
    )

    val recordsAddedCount = rawRecords.zip(recordsAddedResult).count {
      case (record, recordRes) =>
        if (!recordRes.isSuccessful) println(s"Failed to add record to stream: $record")
        recordRes.isSuccessful
    }

    println(s"Put $recordsAddedCount onto fastly-cache-purger-events stream")

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

  /**
   * Send a hard purge request to Fastly API.
   *
   * @return whether a piece of content was purged or not
   */
  def sendFastlyPurgeRequest(contentId: String, purgeType: PurgeType, serviceId: String, surrogateKey: String, fastlyApiKey: String): Boolean = {
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

}
