package com.gu.fastly

import com.amazonaws.services.kinesis.model.Record
import com.amazonaws.services.kinesis.producer.{ KinesisProducer, UserRecordResult }
import com.google.common.util.concurrent.{ FutureCallback, Futures, ListenableFuture }

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ Future, Promise }

object PurgedEventWriter {

  def addRecords(records: List[Record], producer: KinesisProducer): Future[List[UserRecordResult]] = Future.traverse(records)(r =>
    producer.addUserRecord("fastly-cache-purger-events", r.getPartitionKey, r.getData).asScala)

  implicit class RichListenableFuture[T](lf: ListenableFuture[T]) {
    def asScala: Future[T] = {
      val p = Promise[T]()
      Futures.addCallback(lf, new FutureCallback[T] {
        def onFailure(t: Throwable): Unit = p failure t
        def onSuccess(result: T): Unit = p success result
      })
      p.future
    }
  }

}
