package com.gu.googleamp

import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.amazonaws.util.IOUtils
import okhttp3.{ OkHttpClient, Request }
import org.joda.time.DateTime

import java.security.spec.PKCS8EncodedKeySpec
import java.security.{ KeyFactory, PrivateKey, Signature }
import com.gu.fastly.Config
import com.gu.fastly.Config.s3

object AmpFlusher {

  // This object implements AMP flush/delete according to https://developers.google.com/amp/cache/update-cache

  private val s3 = AmazonS3ClientBuilder.defaultClient
  private val httpClient = new OkHttpClient()

  def getCurrentUnixtime(): Long = {
    DateTime.now().getMillis() / 1000
  }

  private def readPrivateKeyFromS3(): Array[Byte] = {
    val inputStream = s3.getObject("fastly-cache-purger-config", "amp-flusher-private-key.der").getObjectContent()
    val key: Array[Byte] = IOUtils.toByteArray(inputStream)
    inputStream.close()
    key
  }

  def getPrivateKey(): PrivateKey = {
    KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(readPrivateKeyFromS3()))
  }

  def computeSignature(data: Array[Byte], privateKey: PrivateKey): Array[Byte] = {
    val signer = Signature.getInstance("SHA256withRSA")
    signer.initSign(privateKey)
    signer.update(data)
    signer.sign()
  }

  def signatureAsWebSafeString(signature: Array[Byte]): String = {
    val signatureBase64Encoded = java.util.Base64.getEncoder.encode(signature).map(_.toChar).mkString
    signatureBase64Encoded.replaceAll("\\+", "-").replaceAll("/", "_") replaceAll ("=", "")
  }

  def makeRequestUrl(contentId: String, timestamp: Long): String = {
    val cacheUpdateRequestURL = s"/update-cache/c/s/amp.theguardian.com/${contentId}?amp_action=flush&amp_ts=${timestamp}"
    val signature = computeSignature(cacheUpdateRequestURL.getBytes, getPrivateKey())
    s"https://amp-theguardian-com.cdn.ampproject.org${cacheUpdateRequestURL}&amp_url_signature=${signatureAsWebSafeString(signature)}"
  }

  def sendAmpDeleteRequest(contentId: String): Boolean = {
    val requestUrl = makeRequestUrl(contentId: String, getCurrentUnixtime())
    val request = new Request.Builder().url(requestUrl).get().build()
    val response = httpClient.newCall(request).execute()
    println(s"Sent amp delete request [contentID: $contentId] [url: ${requestUrl}]. Response from Google AMP CDN: [${response.code}] [${response.body.string}]")
    response.code == 200
  }
}
