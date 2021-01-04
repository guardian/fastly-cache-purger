package com.gu.fastly

import com.amazonaws.services.kinesis.model.Record
import com.gu.contentapi.client.model.v1.ContentType.{Article, Liveblog}
import com.gu.crier.model.event.v1._
import com.gu.thrift.serializer._
import org.scalatest.{MustMatchers, OneInstancePerTest, WordSpecLike}

import java.nio.ByteBuffer
import scala.util.Success

class UpdateDeduplicatorSpec extends WordSpecLike with MustMatchers with OneInstancePerTest {

  "UpdatesDeduplicator must" must {
    val updateContentEvent = Event(
      payloadId = "123",
      eventType = EventType.Update,
      itemType = ItemType.Content,
      dateTime = 100000000L,
      payload = Some(EventPayload.RetrievableContent(RetrievableContent(
        id = "0987654321",
        capiUrl = "http://www.theguardian.com/",
        lastModifiedDate = Some(8888888888L),
        internalRevision = Some(444444),
        contentType = Some(Article),
        aliasPaths = Some(Seq("123", "abc"))
      ))))

    val anotherUpdateContentEvent = Event(
      payloadId = "456",
      eventType = EventType.Update,
      itemType = ItemType.Content,
      dateTime = 100000000L,
      payload = Some(EventPayload.RetrievableContent(RetrievableContent(
        id = "0987654321",
        capiUrl = "http://www.theguardian.com/",
        lastModifiedDate = Some(8888888888L),
        internalRevision = Some(444444),
        contentType = Some(Liveblog),
      ))))

    val deleteContentEvent = Event(
      payloadId = "789",
      eventType = EventType.Delete,
      itemType = ItemType.Content,
      dateTime = 100000000L,
      payload = Some(EventPayload.RetrievableContent(RetrievableContent(
        id = "0987654321",
        capiUrl = "http://www.theguardian.com/",
        lastModifiedDate = Some(8888888888L),
        internalRevision = Some(444444),
        contentType = Some(Liveblog),
      ))))

    "pass delete events for processing" in {
      val deduplicated = UpdateDeduplicator.filterAndDeduplicateContentEvents(Seq(deleteContentEvent))

      deduplicated.size mustEqual 1
      deduplicated mustEqual Seq(deleteContentEvent)
    }

    "deduplicate delete events" in {
      val batchWithDuplicateDeletes = Seq(deleteContentEvent, deleteContentEvent)

      val deduplicated = UpdateDeduplicator.filterAndDeduplicateContentEvents(batchWithDuplicateDeletes)

      deduplicated.size mustEqual 1
      deduplicated mustEqual Seq(deleteContentEvent)
    }

    "deduplicate update events" in {
      val batchWithDuplicates = Seq(deleteContentEvent, updateContentEvent, updateContentEvent, anotherUpdateContentEvent)

      val deduplicated = UpdateDeduplicator.filterAndDeduplicateContentEvents(batchWithDuplicates)

      deduplicated.size mustEqual 3
      deduplicated mustEqual Seq(deleteContentEvent, updateContentEvent, anotherUpdateContentEvent)
    }

  }
}