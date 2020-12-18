package com.gu.fastly

import com.amazonaws.services.cloudwatch.AmazonCloudWatch
import com.amazonaws.services.cloudwatch.model.{ MetricDatum, PutMetricDataRequest, StandardUnit }
import com.amazonaws.services.kinesis.model.Record
import com.gu.crier.model.event.v1.{ Event, EventType, ItemType }
import com.gu.thrift.serializer.ThriftDeserializer

import scala.util.Try

object CrierEventProcessor {

  def process(records: Seq[Record], cloudWatchClient: AmazonCloudWatch)(purge: Event => Boolean) = {

    // Quick check for duplicates
    val crierEvents: Seq[Event] = records.flatMap { record =>
      eventFromRecord(record).toOption
    }
    val updateContentIds = crierEvents.flatMap { event =>
      event.payloadId
      (event.itemType, event.eventType) match {
        case (ItemType.Content, EventType.Update | EventType.RetrievableUpdate) =>
          Some(event.payloadId)
        case _ =>
          None
      }
    }

    val uniqueContentIds = updateContentIds.toSet
    val updateCount = updateContentIds.size
    val uniqueUpdatesCount = uniqueContentIds.size
    println("Batch contained " + updateCount + " content updates ids and " + uniqueUpdatesCount + " unique contentIds")
    if (updateCount > 0 && updateCount != uniqueUpdatesCount) {
      println("Batch may contain duplicate content ids: " + updateContentIds.sorted.mkString(","))

      def sendDuplicatesPercentageMetric(percentage: Double): Unit = {
        val metric = new MetricDatum()
          .withMetricName("duplicate-updates-percentage")
          .withUnit(StandardUnit.None)
          .withValue(percentage)

        val putMetricDataRequest = new PutMetricDataRequest().
          withNamespace("fastly-cache-purger").
          withMetricData(metric)

        try {
          cloudWatchClient.putMetricData(putMetricDataRequest)
        } catch {
          case t: Throwable =>
            println("Warning; cloudwatch metrics ping failed: " + t.getMessage)
        }
      }

      val percent: Int = uniqueUpdatesCount * 100 / updateCount
      sendDuplicatesPercentageMetric(percent)
    }

    val processingResults: Iterable[Boolean] = records.flatMap { record =>
      val event = eventFromRecord(record)
      event.map { e =>
        purge(e)
      }.recover {
        case error =>
          println("Failed to deserialize Crier event from Kinesis record. Skipping.")
          false
      }.toOption
    }
    val purgedCount: Int = processingResults.count(_ == true)
    println(s"Successfully purged $purgedCount pieces of content")
    purgedCount
  }

  private def eventFromRecord(record: Record): Try[Event] = {
    ThriftDeserializer.deserialize(record.getData.array)(Event)
  }

}
