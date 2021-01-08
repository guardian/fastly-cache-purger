package com.gu.fastly

import com.gu.crier.model.event.v1.Event

object CrierEventProcessor {

  def process(crierEvents: Seq[Event])(purge: Event => Boolean): Seq[Event] = {
    val successfulPurges = crierEvents.flatMap { event =>
      Some(event).filter(purge)
    }

    val purgedCount: Int = successfulPurges.size
    println(s"Successfully purged $purgedCount pieces of content")
    successfulPurges
  }

}
