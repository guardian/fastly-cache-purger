package com.gu.fastly

import com.amazonaws.services.kinesis.model.Record
import com.gu.crier.model.event.v1.Event
import com.gu.thrift.serializer.ThriftDeserializer
import scala.util.Try

object CrierEventProcessor {

  def process(events: Seq[Event])(purge: Event => Boolean) = {
    val processingResults: Iterable[Boolean] = events.map { event =>
      purge(event)
    }

    val purgedCount: Int = processingResults.count(_ == true)
    println(s"Successfully purged $purgedCount pieces of content")
    purgedCount
  }

}
