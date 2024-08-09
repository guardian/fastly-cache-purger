package com.gu.fastly

import org.joda.time.DateTime
import org.scalatest.{MustMatchers, OneInstancePerTest, WordSpecLike}

class ContentDecachedEventSerializerSpec
    extends WordSpecLike
    with MustMatchers
    with OneInstancePerTest {

  "Serializer must" must {

    "serialize to a SNS compatible string based format" in {
      val eventPublished = new DateTime(2021, 9, 20, 16, 10, 0, 123)
      val contentDecachedEvent =
        com.gu.fastly.model.event.v1.ContentDecachedEvent(
          contentPath = "/travel/some-content",
          eventType = com.gu.fastly.model.event.v1.EventType.Update,
          contentType =
            Some(com.gu.contentapi.client.model.v1.ContentType.Liveblog),
          eventPublished = Some(eventPublished.getMillis)
        )

      val serialized =
        ContentDecachedEventSerializer.serialize(contentDecachedEvent)

      serialized must include("\"1\":{\"str\":\"/travel/some-content\"")
      serialized must include("\"3\":{\"i32\":1")
      serialized must endWith("}")
    }

  }
}
