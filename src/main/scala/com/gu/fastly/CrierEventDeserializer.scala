package com.gu.fastly

import com.amazonaws.services.kinesis.model.Record
import com.gu.crier.model.event.v1.Event
import com.gu.thrift.serializer.ThriftDeserializer

import scala.util.Try

object CrierEventDeserializer {

  def eventFromRecord(record: Record): Try[Event] = {
    ThriftDeserializer.deserialize(record.getData.array)(Event)
  }

}
