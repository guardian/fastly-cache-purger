
import okhttp3.{ OkHttpClient, Request }

import java.security.{ KeyFactory, KeyPair, KeyPairGenerator, PrivateKey, PublicKey, Signature }
import java.security.spec.{ PKCS8EncodedKeySpec, X509EncodedKeySpec }
import java.nio.file.{ Files, Paths }
import org.joda.time.DateTime

object Main extends App {
  
  private val httpClient = new OkHttpClient()

  def computeCacheUpdateRequestSignature(principalURLFragment: String): Array[Byte] = {
    /*
      reference:
        principalURLFragment = "/update-cache/c/s/amp.theguardian.com/lifeandstyle/2020/dec/22/sex-at-christmas-tends-to-be-off-menu-until-fireworks-at-new-year-study?amp_action=flush&amp_ts=1611582408"
        signature = "HR-NpWisQAcmGlv7wMvzM80eIBhX0161SDuwMTgqsUIooXyuXPh7P6nQszSD3Nn8D0PiRgPX4uONlPb3L8VfN4QIhBBrBwSgnI3OfQ_36ho4KZmBNIFOfwTtvLjgEjpDRf6FAkWUCZZbOMfWZkDut6fd9sL3vWc1fezDcpDm1n7jkVf_UfCY9i9ABvuW1eUvOizuB5JGKFhPIZVXA_1XONRFNJ56tmr2qtjkzuN5aGQ5Ava_KRZNQhNVfrwYerMUOpK0UeHdk3iqhWsJ2cGL4F2Dr-MAlmqDqglt3XVh_WzR6NUWMQZt7TqkhAtN7GLBgm3enpJfT5iyQavFUQNoZA"
     */
    val signer = Signature.getInstance("SHA256withRSA")
    val bytes = Files.readAllBytes(Paths.get("/Users/pascal/Galaxy/Open-Threads/The Guardian NX141-8E97B1C0/Pascal Work Log/B-In Progress/2020-12 amp cache update/NX141-f20b687a-e07b-41f0-bcf4-57760a709324/20-Script/private-key.der"))
    val privateKey: PrivateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes))
    signer.initSign(privateKey)
    signer.update(principalURLFragment.getBytes)
    signer.sign()
  }

  // https://developers.google.com/amp/cache/update-cache
  def sendAmpDeleteRequest(contentId: String): Boolean = {
    val timestamp = DateTime.now().getMillis() / 1000
    val principalURLFragment = s"/update-cache/c/s/amp.theguardian.com/${contentId}?amp_action=flush&amp_ts=${timestamp}"
    val signature = computeCacheUpdateRequestSignature(principalURLFragment)
    val signatureBase64Encoded = java.util.Base64.getEncoder.encode(signature).map(_.toChar).mkString
    val signatureBase64EncodedURLSafe = signatureBase64Encoded.replaceAll("\\+", "-").replaceAll("/", "_") replaceAll ("=", "")
    val requestUrl = s"https://amp-theguardian-com.cdn.ampproject.org${principalURLFragment}&amp_url_signature=${signatureBase64EncodedURLSafe}"
    val request = new Request.Builder().url(requestUrl).get().build()
    val response = httpClient.newCall(request).execute()
    println(s"Sent amp delete request [contentID: $contentId] [ts: $timestamp] [sig: ${signatureBase64EncodedURLSafe}] [url: ${requestUrl}]. Response from Google AMP CDN: [${response.code}] [${response.body.string}]")
    response.code == 200
  }

  sendAmpDeleteRequest("lifeandstyle/2020/dec/22/sex-at-christmas-tends-to-be-off-menu-until-fireworks-at-new-year-study")
}