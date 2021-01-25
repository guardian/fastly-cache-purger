
import okhttp3.{ OkHttpClient, Request }

object Main extends App {

  println("Hello world!")

  private val httpClient = new OkHttpClient()

  def computeCacheUpdateRequestSignature(principalURLFragment: String): String = {
    "HR-NpWisQAcmGlv7wMvzM80eIBhX0161SDuwMTgqsUIooXyuXPh7P6nQszSD3Nn8D0PiRgPX4uONlPb3L8VfN4QIhBBrBwSgnI3OfQ_36ho4KZmBNIFOfwTtvLjgEjpDRf6FAkWUCZZbOMfWZkDut6fd9sL3vWc1fezDcpDm1n7jkVf_UfCY9i9ABvuW1eUvOizuB5JGKFhPIZVXA_1XONRFNJ56tmr2qtjkzuN5aGQ5Ava_KRZNQhNVfrwYerMUOpK0UeHdk3iqhWsJ2cGL4F2Dr-MAlmqDqglt3XVh_WzR6NUWMQZt7TqkhAtN7GLBgm3enpJfT5iyQavFUQNoZA"
  }

  // https://developers.google.com/amp/cache/update-cache
  def sendAmpDeleteRequest(contentId: String): Boolean = {
    val timestamp = 1611582408L.toString
    val principalURLFragment = s"/update-cache/c/s/amp.theguardian.com/${contentId}?amp_action=flush&amp_ts=${timestamp}"
    val signature = computeCacheUpdateRequestSignature(principalURLFragment)
    val requestUrl = s"https://amp-theguardian-com.cdn.ampproject.org${principalURLFragment}&amp_url_signature=${signature}"
    val request = new Request.Builder().url(requestUrl).get().build()
    val response = httpClient.newCall(request).execute()
    println(s"Sent amp delete request [contentID: $contentId] [ts: $timestamp] [sig: ${signature}] [url: ${requestUrl}]. Response from Google AMP CDN: [${response.code}] [${response.body.string}]")
    response.code == 200
  }

  sendAmpDeleteRequest("lifeandstyle/2020/dec/22/sex-at-christmas-tends-to-be-off-menu-until-fireworks-at-new-year-study")
}