package com.gu.fastly

import com.gu.crier.model.event.v1.{ Event, EventType, ItemType }

object UpdateDeduplicator {

  // Filter for Content events then deduplicate updates by content id
  def filterAndDeduplicateContentEvents(events: Seq[Event]): Seq[Event] = {
    // This lambda only processes Content events
    val contentEvents = events.filter(_.itemType == ItemType.Content)

    val updateEvents = contentEvents.filter(event =>
      event.eventType match {
        case EventType.Update => true
        case EventType.RetrievableUpdate => true
        case _ => false
      })

    // Anything which is an update can be past on
    val otherContentEvents = contentEvents.diff(updateEvents)

    // Map by payload id (which is a content id) to merge duplicates
    val deduplicatedContentUpdateEvents = updateEvents.map(updateEvent =>
      updateEvent.payloadId -> updateEvent).toMap.values.toSeq

    if (deduplicatedContentUpdateEvents.size < updateEvents.size) {
      println("Deduplicated content update events (" + events.map(_.payloadId).mkString(", ") + ") to: (" + deduplicatedContentUpdateEvents.map(_.payloadId).mkString(", ") + ")")
    }

    otherContentEvents ++ deduplicatedContentUpdateEvents
  }

}
