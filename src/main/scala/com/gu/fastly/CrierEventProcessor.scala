package com.gu.fastly

import com.amazonaws.services.kinesis.model.Record
import com.gu.crier.model.event.v1.Event
import com.gu.thrift.serializer.ThriftDeserializer

import scala.util.{ Success, Try }

object CrierEventProcessor {
  def decodeRecord(record: Record): Try[Event] = {
    val tryEvent = ThriftDeserializer.deserialize(record.getData.array)(Event)
    tryEvent.failed.foreach(_ => println("Failed to deserialize Crier event from Kinesis record. Skipping."))
    tryEvent
  }

  def successfulEvents: PartialFunction[Try[Event], Event] = { case Success(x) => x }
}
