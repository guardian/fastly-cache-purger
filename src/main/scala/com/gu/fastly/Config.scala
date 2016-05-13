package com.gu.fastly

import java.util.Properties
import com.amazonaws.services.s3.AmazonS3Client
import scala.util.Try

case class Config(fastlyServiceId: String, fastlyApiKey: String)

object Config {

  val s3 = new AmazonS3Client()

  def load(): Config = {
    println("Loading config...")
    val properties = loadProperties("fastly-cache-purger-config", "fastly-cache-purger.properties") getOrElse sys.error("Could not load config file from s3. This lambda will not run.")

    val fastlyServiceId = getMandatoryConfig(properties, "fastly.serviceId")
    println(s"Fastly service ID = $fastlyServiceId")

    val fastlyApiKey = getMandatoryConfig(properties, "fastly.apiKey")
    println(s"Fastly API key = ${fastlyApiKey.take(3)}...")

    Config(fastlyServiceId, fastlyApiKey)
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

}

