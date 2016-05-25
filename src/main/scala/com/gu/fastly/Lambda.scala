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
          sendPurgeRequest(event.payloadId, Hard)
        case (ItemType.Content, EventType.Update) =>
          sendPurgeRequest(event.payloadId, Soft)
        case (ItemType.Content, EventType.RetrievableUpdate) =>
          sendPurgeRequest(event.payloadId, Soft)

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
  private object Soft extends PurgeType
  private object Hard extends PurgeType

  /**
   * Send a hard purge request to Fastly API.
   *
   * @return whether a piece of content was purged or not
   */
  private def sendPurgeRequest(contentId: String, purgeType: PurgeType): Boolean = {
    val contentPath = s"/$contentId"
    val surrogateKey = DigestUtils.md5Hex(contentPath)
    val url = s"https://api.fastly.com/service/${config.fastlyServiceId}/purge/$surrogateKey"

    val requestBuilder = new Request.Builder()
      .url(url)
      .header("Fastly-Key", config.fastlyApiKey)
      .post(EmptyJsonBody)

    val request = (purgeType match {
      case Soft => requestBuilder.header("Fastly-Soft-Purge", "1")
      case _ => requestBuilder
    }).build()

    val response = httpClient.newCall(request).execute()
    println(s"Sent ${purgeType.getClass.getSimpleName} purge request for content with ID [$contentId]. Response from Fastly API: [${response.code}] [${response.body.string}]")

    val purged = response.code == 200
    purged
  }
}
