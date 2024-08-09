package com.gu.googleamp

import okhttp3.{OkHttpClient, Request}
import org.joda.time.DateTime
import java.security.spec.PKCS8EncodedKeySpec
import java.security.{KeyFactory, PrivateKey, Signature}
import com.gu.fastly.Config

object AmpFlusher {

  // This object implements AMP flush/delete according to https://developers.google.com/amp/cache/update-cache

  // There also is this documentation: fastly-cache-purger/docs/Google AMP Cache Flush

  private val httpClient = new OkHttpClient()
  private val config = Config.load()

  def sendAmpDeleteRequest(contentId: String): Boolean = {
    val requestUrl = makeRequestUrl(contentId: String, getCurrentUnixtime())
    val request = new Request.Builder().url(requestUrl).get().build()
    val response = httpClient.newCall(request).execute()
    println(
      s"Sent amp delete request [contentID: $contentId] [url: ${requestUrl}]. Response from Google AMP CDN: [${response.code}] [${response.body.string}]"
    )
    response.code == 200
  }

  private def getCurrentUnixtime(): Long = {
    DateTime.now().getMillis() / 1000
  }

  private def getPrivateKey(): PrivateKey = {
    val bytes = config.ampFlusherPrivateKey
    KeyFactory
      .getInstance("RSA")
      .generatePrivate(new PKCS8EncodedKeySpec(bytes))
  }

  private def computeSignature(
      data: Array[Byte],
      privateKey: PrivateKey
  ): Array[Byte] = {
    val signer = Signature.getInstance("SHA256withRSA")
    signer.initSign(privateKey)
    signer.update(data)
    signer.sign()
  }

  private def signatureAsWebSafeString(signature: Array[Byte]): String = {
    val signatureBase64Encoded =
      java.util.Base64.getEncoder.encode(signature).map(_.toChar).mkString
    signatureBase64Encoded
      .replaceAll("\\+", "-")
      .replaceAll("/", "_") replaceAll ("=", "")
  }

  private def makeRequestUrl(contentId: String, timestamp: Long): String = {
    val cacheUpdateRequestURL =
      s"/update-cache/c/s/amp.theguardian.com/${contentId}?amp_action=flush&amp_ts=${timestamp}"
    val signature =
      computeSignature(cacheUpdateRequestURL.getBytes, getPrivateKey())
    s"https://amp-theguardian-com.cdn.ampproject.org${cacheUpdateRequestURL}&amp_url_signature=${signatureAsWebSafeString(signature)}"
  }

}
