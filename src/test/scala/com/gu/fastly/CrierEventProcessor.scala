package com.gu.fastly

import com.amazonaws.services.kinesis.model.Record
import com.gu.crier.model.event.v1.{ Event, EventPayload, EventType, ItemType, RetrievableContent }
import com.gu.thrift.serializer._
import java.nio.ByteBuffer

import com.gu.fastly.CrierEventProcessor.{ decodeRecord, successfulEvents }
import org.scalatest.{ MustMatchers, OneInstancePerTest, WordSpecLike }

class CrierEventProcessorSpec extends WordSpecLike with MustMatchers with OneInstancePerTest {

  "Crier Event Processor must" must {
    val event = Event(
      payloadId = "1234567890",
      eventType = EventType.Update,
      itemType = ItemType.Tag,
      dateTime = 100000000L,
      payload = Some(EventPayload.RetrievableContent(RetrievableContent(
        id = "0987654321",
        capiUrl = "http://www.theguardian.com/",
        lastModifiedDate = Some(8888888888L),
        internalRevision = Some(444444)
      )))
    )

    "properly deserialize a compressed event" in {
      val bytes = ThriftSerializer.serializeToBytes(event, Some(ZstdType), None)
      val record = new Record().withData(ByteBuffer.wrap(bytes))
      (List(record).map(decodeRecord) collect successfulEvents).size mustEqual 1
    }

    "properly deserialize a non-compressed event" in {
      val bytes = ThriftSerializer.serializeToBytes(event, None, None)
      val record = new Record().withData(ByteBuffer.wrap(bytes))
      (List(record).map(decodeRecord) collect successfulEvents).size mustEqual 1
    }
  }
}