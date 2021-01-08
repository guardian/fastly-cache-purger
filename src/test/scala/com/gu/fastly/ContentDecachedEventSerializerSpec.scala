package com.gu.fastly

import org.scalatest.{ MustMatchers, OneInstancePerTest, WordSpecLike }

class ContentDecachedEventSerializerSpec extends WordSpecLike with MustMatchers with OneInstancePerTest {

  "Serializer must" must {

    "serialize to a SNS compatible string based format" in {
      val contentDecachedEvent =
        com.gu.fastly.model.event.v1.ContentDecachedEvent(
          contentId = "/travel/some-content",
          eventType = com.gu.fastly.model.event.v1.EventType.Update
        )

      val serialized = ContentDecachedEventSerializer.serialize(contentDecachedEvent)

      serialized must include("\"1\":{\"str\":\"/travel/some-content\"")
    }

  }
}