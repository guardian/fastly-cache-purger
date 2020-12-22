package com.gu.fastly

import com.gu.crier.model.event.v1.Event

object CrierEventProcessor {

  def process(crierEvents: Seq[Event])(purge: Event => Boolean) = {
    crierEvents.map { event =>
      purge(event)
    }

    val purgedCount: Int = crierEvents.size
    println(s"Successfully purged $purgedCount pieces of content")
    purgedCount
  }

}
