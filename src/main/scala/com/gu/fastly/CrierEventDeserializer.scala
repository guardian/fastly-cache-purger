package com.gu.fastly

import com.amazonaws.services.kinesis.clientlibrary.types.UserRecord
import com.amazonaws.services.kinesis.model.Record
import com.gu.crier.model.event.v1.Event
import com.gu.thrift.serializer.ThriftDeserializer

import scala.collection.mutable
import scala.util.{ Failure, Success, Try }

object CrierEventDeserializer {

  def deserializeEvents(scala: mutable.Buffer[UserRecord]): Seq[Event] = {
    scala.flatMap { record =>
      CrierEventDeserializer.eventFromRecord(record) match {
        case Success(event) =>
          Some(event)
        case Failure(error) =>
          println("Failed to deserialize Crier event from Kinesis record. Skipping.")
          None
      }
    }
  }
  def eventFromRecord(record: Record): Try[Event] = {
    ThriftDeserializer.deserialize(record.getData.array)(Event)
  }

}
