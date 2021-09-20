package com.gu.fastly

import com.gu.fastly.model.event.v1.ContentDecachedEvent
import org.apache.thrift.protocol.TJSONProtocol
import org.apache.thrift.transport.TMemoryBuffer

import java.nio.charset.StandardCharsets

object ContentDecachedEventSerializer {

  def serialize(event: ContentDecachedEvent): String = {
    val buffer = new TMemoryBuffer(128)
    val protocol = new TJSONProtocol(buffer)
    event.write(protocol)
    buffer.toString(StandardCharsets.UTF_8)
  }

}
