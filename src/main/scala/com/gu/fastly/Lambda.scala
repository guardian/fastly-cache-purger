package com.gu.fastly

import com.amazonaws.services.kinesis.clientlibrary.types.UserRecord
import com.amazonaws.services.kinesis.model.Record
import com.amazonaws.services.lambda.runtime.events.KinesisEvent
import com.gu.crier.model.event.v1._
import io.circe.generic.auto._
import io.circe.parser._
import okhttp3._
import org.apache.commons.codec.digest.DigestUtils

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
          sendFastlyPurgeRequestAndAmpPingRequest(event.payloadId, Hard, config.fastlyDotcomServiceId, makeDotcomSurrogateKey(event.payloadId), config.fastlyDotcomApiKey)
          sendFacebookNewsitemDenylistRequest(event.payloadId)

        case (ItemType.Content, EventType.Update) =>
          sendFastlyPurgeRequest(event.payloadId, Soft, config.fastlyDotcomServiceId, makeDotcomSurrogateKey(event.payloadId), config.fastlyDotcomApiKey)
          sendFastlyPurgeRequest(event.payloadId, Soft, config.fastlyMapiServiceId, makeMapiSurrogateKey(event.payloadId), config.fastlyMapiApiKey)
          sendFastlyPurgeRequestForLiveblogAjaxFiles(event.payloadId) // Why does this only have to happen for content updates which are small enough not to have been sent as RetrievableUpdate?
          sendFacebookNewstabPing(event.payloadId)

        case (ItemType.Content, EventType.RetrievableUpdate) =>
          sendFastlyPurgeRequest(event.payloadId, Soft, config.fastlyDotcomServiceId, makeDotcomSurrogateKey(event.payloadId), config.fastlyDotcomApiKey)
          sendFastlyPurgeRequest(event.payloadId, Soft, config.fastlyMapiServiceId, makeMapiSurrogateKey(event.payloadId), config.fastlyMapiApiKey)
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

  private def sendFastlyPurgeRequestForLiveblogAjaxFiles(contentId: String) = {
    sendFastlyPurgeRequest(s"${contentId}.json", Soft, config.fastlyApiNextgenServiceId, makeDotcomSurrogateKey(s"${contentId}.json"), config.fastlyDotcomApiKey)
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

  /**
   * Add the article to the News Tab denylist hiding the article from the News Tab.
   */
  def sendFacebookNewsitemDenylistRequest(contentId: String): Boolean = {
    // In the long run we'd expect to inform Facebook of all take downs even if they we not actually in scope for Newstab.
    // For the proof of concept we will limit take downs to the same set of content is we are explicitly indexing.
    val isThisDeleteInScopeForProofOfConcept = contentIsInterestingToFacebookNewstab(contentId)
    if (isThisDeleteInScopeForProofOfConcept) {
      val contentWebUrl = webUrlFor(contentId)
      val scope = config.facebookNewsTabScope

      val denylistArticle = new HttpUrl.Builder()
        .scheme("https")
        .host("graph.facebook.com")
        .addQueryParameter("id", contentWebUrl)
        .addQueryParameter("scopes", scope)
        .addQueryParameter("denylist", "true")
        .addQueryParameter("access_token", config.facebookNewsTabAccessToken)
        .build();

      val request = new Request.Builder()
        .url(denylistArticle)
        .post(EmptyJsonBody)
        .build()

      val response = httpClient.newCall(request).execute()

      // Soft evaluate the Facebook response
      // Their documentation does not specifically mention response codes.
      // Lets evaluate and log our interpretation of the response for now
      val wasSuccessful = isExpectedFacebookNewstabResponse(response, scope, "DENYLISTED")

      println(s"Sent Facebook Newstab denylist request for content with url [$contentWebUrl]. " +
        s"Response from Facebook: [${response.code}] [${response.body.string}]. " +
        s"Was successful: [$wasSuccessful]")
    }

    true // Always return true during the proof on concept until we are confident about Facebook's responses
  }

  /**
   * If this content update is editorially interesting to Facebook Newstab ping their update end point.
   *
   * @return decision and/or ping completed successfully
   */
  def sendFacebookNewstabPing(contentId: String): Boolean = {
    if (contentIsInterestingToFacebookNewstab(contentId)) {
      val contentWebUrl = webUrlFor(contentId)
      val scope = config.facebookNewsTabScope

      // POST endpoint with URL encoded parameters as per New Tab documentation
      val indexArticle = new HttpUrl.Builder()
        .scheme("https")
        .host("graph.facebook.com")
        .addQueryParameter("id", contentWebUrl)
        .addQueryParameter("scopes", scope)
        .addQueryParameter("scrape", "true")
        .addQueryParameter("access_token", config.facebookNewsTabAccessToken)
        .build();

      val request = new Request.Builder()
        .url(indexArticle)
        .post(EmptyJsonBody)
        .build()

      val response = httpClient.newCall(request).execute()

      // Soft evaluate the Facebook response
      // Their documentation does not specifically mention response codes.
      // Lets evaluate and log our interpretation of the response for now
      val wasSuccessful = isExpectedFacebookNewstabResponse(response, scope, "INDEXED")

      println(s"Sent Facebook Newstab ping request for content with url [$contentWebUrl]. " +
        s"Response from Facebook: [${response.code}] [${response.body.string}]. " +
        s"Was successful: [$wasSuccessful]")

      true // Always return true during the proof on concept until we are confident about Facebook's responses

    } else {
      true
    }
  }

  private def contentIsInterestingToFacebookNewstab(contentId: String) = {
    // This is an interesting question which will almost certainly by iterated on.
    // Basing this decision entirely on the contentId is unlikely to age well.
    // Our opening move for the proof of concept is to dibble a small amount of content which is unlikely to be taken down.
    // Travel articles sound safe.
    contentId.contains("travel/2020")
  }

  private def webUrlFor(contentId: String) = {
    val contentPath = s"/$contentId"
    s"https://www.theguardian.com${contentPath}"
  }

  private def isExpectedFacebookNewstabResponse(response: Response, scope: String, expected: String): Boolean = {
    response.code match {
      case 200 =>
        decode[FacebookNewstabResponse](response.body.string()).fold({ error =>
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

  private case class FacebookNewstabResponse(url: String, scopes: Map[String, String])

}
