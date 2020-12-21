package com.gu.fastly

import com.amazonaws.services.kinesis.model.Record
import com.gu.crier.model.event.v1.Event
import com.gu.thrift.serializer.ThriftDeserializer

import scala.util.{Failure, Success, Try}

object CrierEventProcessor {

  def process(records: Seq[Record])(purge: Event => Boolean) = {
    val crierEvents = records.flatMap { record =>
      eventFromRecord(record) match {
        case Success(event) =>
          Some(event)
        case Failure(error) =>
          println("Failed to deserialize Crier event from Kinesis record. Skipping: " + error.getMessage)
          None
      }
    }

    crierEvents.map { event =>
      purge(event)
    }

    val purgedCount: Int = crierEvents.size
    println(s"Successfully purged $purgedCount pieces of content")
    purgedCount
  }

  private def eventFromRecord(record: Record): Try[Event] = {
    ThriftDeserializer.deserialize(record.getData.array)(Event)
  }

}
