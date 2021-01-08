package com.gu.fastly

import com.amazonaws.services.kinesis.model.Record
import com.gu.contentapi.client.model.v1.ContentType.Article
import com.gu.crier.model.event.v1._
import com.gu.thrift.serializer._
import org.scalatest.{ MustMatchers, OneInstancePerTest, WordSpecLike }

import java.nio.ByteBuffer
import scala.util.Success

class CrierDeserializerSpec extends WordSpecLike with MustMatchers with OneInstancePerTest {

  "Deserializer must" must {
    val event = Event(
      payloadId = "1234567890",
      eventType = EventType.Update,
      itemType = ItemType.Tag,
      dateTime = 100000000L,
      payload = Some(EventPayload.RetrievableContent(RetrievableContent(
        id = "0987654321",
        capiUrl = "http://www.theguardian.com/",
        lastModifiedDate = Some(8888888888L),
        internalRevision = Some(444444),
        contentType = Some(Article),
        aliasPaths = Some(Seq("123", "abc"))
      )))
    )

    "properly deserialize a compressed event" in {
      val bytes = ThriftSerializer.serializeToBytes(event, Some(ZstdType), None)
      val compressedEventRecord = new Record().withData(ByteBuffer.wrap(bytes))
      CrierEventDeserializer.eventFromRecord(compressedEventRecord) mustEqual Success(event)
    }

    "properly deserialize a non-compressed event" in {
      val bytes = ThriftSerializer.serializeToBytes(event, None, None)
      val eventRecord = new Record().withData(ByteBuffer.wrap(bytes))
      CrierEventDeserializer.eventFromRecord(eventRecord) mustEqual Success(event)
    }
  }
}