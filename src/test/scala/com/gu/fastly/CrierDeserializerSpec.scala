package com.gu.fastly

import com.amazonaws.services.kinesis.model.Record
import com.gu.contentapi.client.model.v1.{ AliasPath, CapiDateTime }
import com.gu.contentapi.client.model.v1.ContentType.Article
import com.gu.crier.model.event.v1._
import com.gu.thrift.serializer._
import org.joda.time.DateTime
import org.joda.time.DateTimeZone.UTC
import org.scalatest.{ MustMatchers, OneInstancePerTest, WordSpecLike }

import java.nio.ByteBuffer
import scala.util.Success

class CrierDeserializerSpec extends WordSpecLike with MustMatchers with OneInstancePerTest {
  val dt1 = DateTime.now().minusDays(3)
  val dt2 = DateTime.now().minusDays(2)
  val fakeAliasPaths = Seq(
    AliasPath("123", CapiDateTime(dt1.getMillis, dt1.withZone(UTC).toString())),
    AliasPath("abc", CapiDateTime(dt2.getMillis, dt2.withZone(UTC).toString())))

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
        aliasPaths = Some(fakeAliasPaths)))))

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