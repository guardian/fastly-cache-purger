package com.gu.fastly

import com.amazonaws.services.kinesis.model.Record
import com.gu.crier.model.event.v1.{Event, EventType, ItemType}
import com.gu.thrift.serializer.ThriftDeserializer

import scala.util.Try

object CrierEventProcessor {

  def process(records: Seq[Record])(purge: Event => Boolean) = {

    // Quick check for duplicates
    val crierEvents: Seq[Event] = records.flatMap { record =>
      eventFromRecord(record).toOption
    }
    val updateContentIds = crierEvents.flatMap { event =>
      event.payloadId
      (event.itemType, event.eventType) match {
        case (ItemType.Content, EventType.Update | EventType.RetrievableUpdate) =>
          Some(event.payloadId)
        case _ =>
          None
      }
    }
    val uniqueContentIds = updateContentIds.toSet
    println("Batch contained " + updateContentIds.size + " content updates ids and " + uniqueContentIds.size + " unique contentIds")
    if (updateContentIds.size != uniqueContentIds.size) {
      println("Batch may contain duplicate content ids")
    }

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
    purgedCount
  }

  private def eventFromRecord(record: Record): Try[Event] = {
    ThriftDeserializer.deserialize(record.getData.array)(Event)
  }

}
