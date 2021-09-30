package com.gu.fastly

import java.util.Properties
import scala.util.Try
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.amazonaws.util.IOUtils

case class Config(
  fastlyDotcomServiceId: String,
  fastlyMapiServiceId: String,
  fastlyApiNextgenServiceId: String,
  fastlyDotcomApiKey: String,
  fastlyMapiApiKey: String,
  decachedContentTopic: String,
  ampFlusherPrivateKey: Array[Byte])

object Config {

  val s3 = AmazonS3ClientBuilder.defaultClient

  def load(): Config = {
    val properties = loadProperties("fastly-cache-purger-config", "fastly-cache-purger.properties") getOrElse sys.error("Could not load config file from s3. This lambda will not run.")

    val fastlyDotcomServiceId = getMandatoryConfig(properties, "fastly.serviceId")

    val fastlyDotcomApiKey = getMandatoryConfig(properties, "fastly.apiKey")

    val fastlyGuardianAppsServiceId = getMandatoryConfig(properties, "fastly.apiNextgen.serviceId")

    val fastlyMapiServiceId = getMandatoryConfig(properties, "fastly.MapiServiceId")

    val fastlyMapiApiKey = getMandatoryConfig(properties, "fastly.MapiApiKey")

    val decachedContentTopic = getMandatoryConfig(properties, "decached.content.topic")

    Config(
      fastlyDotcomServiceId,
      fastlyMapiServiceId,
      fastlyGuardianAppsServiceId,
      fastlyDotcomApiKey,
      fastlyMapiApiKey,
      decachedContentTopic,
      getAmpFlusherPrivateKey())
  }

  private def loadProperties(bucket: String, key: String): Try[Properties] = {
    val inputStream = s3.getObject(bucket, key).getObjectContent()
    val properties: Properties = new Properties()
    val result = Try(properties.load(inputStream)).map(_ => properties)
    inputStream.close()
    result
  }

  private def getMandatoryConfig(config: Properties, key: String) =
    Option(config.getProperty(key)) getOrElse sys.error(s"''$key' property missing.")

  private def getAmpFlusherPrivateKey(): Array[Byte] = {
    val inputStream = s3.getObject("fastly-cache-purger-config", "amp-flusher-private-key.der").getObjectContent()
    val key: Array[Byte] = IOUtils.toByteArray(inputStream)
    inputStream.close()
    key
  }
}

