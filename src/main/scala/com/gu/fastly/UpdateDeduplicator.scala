package com.gu.fastly

import com.gu.crier.model.event.v1.{ Event, EventType, ItemType }

object UpdateDeduplicator {

  // Filter for Content events then deduplicate updates by content id
  def filterAndDeduplicateContentEvents(events: Seq[Event]): Seq[Event] = {
    // This lambda only processes Content events
    val contentEvents = events.filter(_.itemType == ItemType.Content)

    val contentUpdateEvents = contentEvents.filter(event =>
      event.eventType match {
        case EventType.Update => true
        case EventType.RetrievableUpdate => true
        case _ => false
      })

    val contentDeleteEvents = contentEvents.filter(event =>
      event.eventType match {
        case EventType.Delete => true
        case _ => false
      })

    // Anything which is an update can be past on
    val otherContentEvents = contentEvents.diff(contentUpdateEvents ++ contentDeleteEvents)

    // Map by payload id (which is a content id) to merge duplicates
    val deduplicatedContentUpdateEvents = duplicateByPayloadId(contentUpdateEvents)
    if (deduplicatedContentUpdateEvents.size < contentUpdateEvents.size) {
      println("Deduplicated content update events (" + contentUpdateEvents.map(_.payloadId).mkString(", ") + ") to: (" + deduplicatedContentUpdateEvents.map(_.payloadId).mkString(", ") + ")")
    }

    val deduplicatedContentDeleteEvents = duplicateByPayloadId(contentDeleteEvents)
    if (deduplicatedContentDeleteEvents.size < contentDeleteEvents.size) {
      println("Deduplicated content delete events (" + contentDeleteEvents.map(_.payloadId).mkString(", ") + ") to: (" + deduplicatedContentDeleteEvents.map(_.payloadId).mkString(", ") + ")")
    }

    otherContentEvents ++ deduplicatedContentDeleteEvents ++ deduplicatedContentUpdateEvents
  }

  private def duplicateByPayloadId(events: Seq[Event]): Seq[Event] = {
    events.map(event => event.payloadId -> event).toMap.values.toSeq
  }

}
