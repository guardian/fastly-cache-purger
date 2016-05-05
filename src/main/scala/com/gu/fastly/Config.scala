package com.gu.fastly

import java.util.Properties
import com.amazonaws.services.s3.AmazonS3Client
import scala.util.Try

case class Config(fastlyServiceId: String, fastlyApiKey: String)

object Config {

  val s3 = new AmazonS3Client()

  def load(): Config = {
    println("Loading config...")
    val configFileKey = s"fastly-cache-purger.properties"
    val configInputStream = s3.getObject("fastly-cache-purger-config", configFileKey).getObjectContent()
    val configFile: Properties = new Properties()
    Try(configFile.load(configInputStream)) orElse sys.error("Could not load config file from s3. This lambda will not run.")

    val fastlyServiceId = getMandatoryConfig(configFile, "fastly.serviceId")
    println(s"Fastly service ID = $fastlyServiceId")

    val fastlyApiKey = getMandatoryConfig(configFile, "fastly.apiKey")
    println(s"Fastly API key = ${fastlyApiKey.take(3)}...")

    Config(fastlyServiceId, fastlyApiKey)
  }

  private def getMandatoryConfig(config: Properties, key: String) =
    Option(config.getProperty(key)) getOrElse sys.error(s"''$key' property missing.")

}

