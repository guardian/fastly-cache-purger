package com.gu.fastly

import com.amazonaws.services.kinesis.model.Record
import com.gu.crier.model.event.v1.Event
import com.gu.thrift.serializer.ThriftDeserializer
import scala.util.Try

object CrierEventProcessor {

  def process(records: Seq[Record])(purge: Event => Option[Event]): Seq[Event] = {

    def processRecord(record: Record): Option[Event] = eventFromRecord(record).flatMap { e =>
      Try(purge(e))
    }.recover {
      case error =>
        println("Failed to deserialize Crier event from Kinesis record. Skipping.")
        None
    }.toOption.flatten

    val processedRecords = records.flatMap(processRecord)
    println(s"Successfully purged ${processedRecords.length} pieces of content")
    processedRecords
  }

  private def eventFromRecord(record: Record): Try[Event] = {
    ThriftDeserializer.deserialize(record.getData.array)(Event)
  }

}
