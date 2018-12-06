package com.gu.fastly

import com.amazonaws.services.kinesis.model.Record
import com.gu.crier.model.event.v1.Event
import com.gu.thrift.serializer.ThriftDeserializer
import scala.util.Try

object CrierEventProcessor {

  def process(records: Seq[Record])(purge: Event => Boolean) = {
    val processingResults: Iterable[Boolean] = records.flatMap { record =>
      val event = eventFromRecord(record)
      event.map { e =>
        purge(e)
      }.recover {
        case error =>
          println("Failed to deserialize Crier event from Kinesis record. Skipping.")
          false
      }.toOption
    }
    val purgedCount: Int = processingResults.count(_ == true)
    println(s"Successfully purged $purgedCount pieces of content")
  }

  private def eventFromRecord(record: Record): Try[Event] = {
    ThriftDeserializer.deserialize(record.getData.array)(Event)
  }

}
