package com.gu.fastly

import com.amazonaws.services.kinesis.model.Record
import com.gu.crier.model.event.v1.Event
import com.gu.thrift.serializer.ThriftDeserializer

import scala.util.{Failure, Success, Try}

object CrierEventDeserializer {

  def eventsFromRecords(records: Seq[Record]): Seq[Event] = {
    records.flatMap { record =>
      CrierEventDeserializer.eventFromRecord(record) match {
        case Success(event) =>
          Some(event)
        case Failure(error) =>
          println("Failed to deserialize Crier event from Kinesis record. Skipping: " + error.getMessage)
          None
      }
    }
  }

  def eventFromRecord(record: Record): Try[Event] = {
    ThriftDeserializer.deserialize(record.getData.array)(Event)
  }

}
