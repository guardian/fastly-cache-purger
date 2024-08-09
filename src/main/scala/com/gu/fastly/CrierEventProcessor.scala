package com.gu.fastly

import com.gu.crier.model.event.v1.Event

object CrierEventProcessor {

  def process(
      crierEvents: Seq[Event]
  )(purge: Event => Option[Decache]): Seq[Decache] = {
    val successfulPurges = crierEvents.map(purge).flatten
    println(s"Successfully purged ${successfulPurges.size} pieces of content")
    successfulPurges
  }

}
