package com.gu.fastly

import com.amazonaws.services.kinesis.clientlibrary.types.UserRecord
import com.amazonaws.services.kinesis.model.Record
import com.amazonaws.services.lambda.runtime.events.KinesisEvent
import okhttp3._
import scala.collection.JavaConverters._

class Lambda {

  private val config = Config.load()
  private val httpClient = new OkHttpClient()

  def recordHandler(event: KinesisEvent) {
    val rawRecords: List[Record] = event.getRecords.asScala.map(_.getKinesis).toList
    val userRecords = UserRecord.deaggregate(rawRecords.asJava)

    println(s"Processing ${userRecords.size} records ...")

    CrierEventProcessor.process(userRecords.asScala) { event =>
      // TODO calculate surrogate key (md5hex of path)
      // TODO if it's a delete event, send purge request to Fastly

      true
    }

    println(s"Finished.")
  }

}
