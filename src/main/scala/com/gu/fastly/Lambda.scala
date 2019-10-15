package com.gu.fastly

import org.apache.commons.codec.digest.DigestUtils
import com.amazonaws.services.kinesis.clientlibrary.types.UserRecord
import com.amazonaws.services.kinesis.model.Record
import com.amazonaws.services.lambda.runtime.events.KinesisEvent
import okhttp3._
import com.gu.crier.model.event.v1._
import scala.collection.JavaConverters._

class Lambda {

  private val config = Config.load()
  private val httpClient = new OkHttpClient()

  def handle(event: KinesisEvent) {
    val rawRecords: List[Record] = event.getRecords.asScala.map(_.getKinesis).toList
    val userRecords = UserRecord.deaggregate(rawRecords.asJava)

    println(s"Processing ${userRecords.size} records ...")

    CrierEventProcessor.process(userRecords.asScala) { event =>
      (event.itemType, event.eventType) match {
        case (ItemType.Content, EventType.Delete) =>
          sendFastlyPurgeRequestAndAmpPingRequest(event.payloadId, Hard, config.fastlyDotcomServiceId, DotcomSurrogateKey(event.payloadId), config.fastlyDotcomApiKey)
        case (ItemType.Content, EventType.Update) =>
          sendFastlyPurgeRequest(event.payloadId, Soft, config.fastlyDotcomServiceId, DotcomSurrogateKey(event.payloadId), config.fastlyDotcomApiKey)
          sendFastlyPurgeRequest(s"${event.payloadId}.json", Soft, config.fastlyApiNextgenServiceId, DotcomSurrogateKey(s"${event.payloadId}.json"), config.fastlyDotcomApiKey)
          sendFastlyPurgeRequest(s"${event.payloadId}.json", Soft, config.fastlyMapiServiceId, MapiSurrogateKey(), config.fastlyMapiApiKey)
        case (ItemType.Content, EventType.RetrievableUpdate) =>
          sendFastlyPurgeRequest(event.payloadId, Soft, config.fastlyDotcomServiceId, DotcomSurrogateKey(event.payloadId), config.fastlyDotcomApiKey)
          sendFastlyPurgeRequest(event.payloadId, Soft, config.fastlyMapiServiceId, MapiSurrogateKey(), config.fastlyMapiApiKey)

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

  private sealed trait SurrogateType { def toSurrogateKey: String }
  case class DotcomSurrogateKey(contentId: String) extends SurrogateType {
    override def toSurrogateKey: String = {
      val contentPath = s"/$contentId"
      val dotcomSurrogateKey = DigestUtils.md5Hex(contentPath)
      dotcomSurrogateKey
    }
  }
  case class MapiSurrogateKey() extends SurrogateType { override def toSurrogateKey = "Item" }

  private def sendFastlyPurgeRequestAndAmpPingRequest(contentId: String, purgeType: PurgeType, serviceId: String, surrogateKey: SurrogateType, fastlyApiKey: String): Boolean = {
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
    def sendFastlyPurgeRequest(contentId: String, purgeType: PurgeType, serviceId: String, surrogateKey: SurrogateType, fastlyApiKey: String): Boolean = {
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
      println(s"Sent $purgeType purge request for content with ID [$contentId]. Response from Fastly API: [${response.code}] [${response.body.string}]")

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
