/*
 *  Copyright 2014 Joshua Goldie
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package akka.persistence.inmem.journal

import akka.actor.{ActorRef, ActorSystem, Actor, ActorLogging}
import akka.persistence.journal.{AsyncWriteTarget, AsyncWriteProxy, AsyncRecovery, AsyncWriteJournal}
import akka.persistence.{Persistence, PersistentConfirmation, PersistentId, PersistentRepr}
import akka.util.Timeout

import scala.collection.immutable.Seq
import scala.collection.mutable.{HashMap, MultiMap, Set}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util._


object InMemoryMessageStore {
  private val mm = new HashMap[String, Set[PersistentRepr]] with MultiMap[String, PersistentRepr]

  def truncate(): Unit =
    mm.clear()
}

/**
 * A simple class that provides some basic CRUD functions on a HashMap + MultiMap to support
 * journal plugins.
 */
trait InMemoryMessageStore {

  import InMemoryMessageStore.mm

  /**
   * Adds messages to the multi map for the given persistence Id
   * @param messages
   */
  def addMessages(messages: Seq[PersistentRepr]) = messages.foreach(msg => mm.addBinding(msg.persistenceId, msg))

  /**
   * Returns a Seq of messages for a given persistenceId that fall between the given sequence numbers.  The sequence
   * is sorted in order of sequence number and is limited to the maximum specified number of elements.
   * @param persistenceId
   * @param fromSequenceNr
   * @param toSequenceNr
   * @param max
   * @return
   */
  def getMessages(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long)  = {
    //take() needs an int - since this is for testing, the loss of upper range should be OK
    val maxReplay = if (max > Int.MaxValue.toLong) Int.MaxValue else max.toInt
    mm.get(persistenceId) match {
      case None => Seq[PersistentRepr]()
      case Some(m: Set[PersistentRepr]) =>
        m.filter(p => p.sequenceNr >= fromSequenceNr && p.sequenceNr <= toSequenceNr)
          .toSeq.sortBy(_.sequenceNr).take(maxReplay)
    }
  }

  /**
   * Finds the greatest message sequence number for a given persistenceId
   * @param persistenceId
   * @param fromSequenceNr
   * @return
   */
  def getHighestSeq(persistenceId: String, fromSequenceNr: Long) =
    mm.get(persistenceId) match {
      case None => 0L
      case Some(m: Set[PersistentRepr]) => m.filter(p => p.sequenceNr >= fromSequenceNr)
        .reduceLeft((m1, m2) => latestMessage(m1, m2)).sequenceNr
    }

  /**
   * Returns message with latest sequence number.  No need to check equality of sequence
   * since that will not happen.
   * @param m1
   * @param m2
   * @return
   */
  private[this] def latestMessage(m1: PersistentRepr, m2: PersistentRepr) : PersistentRepr =
    if (m2.sequenceNr > m1.sequenceNr) m2 else m1

  /**
   * Deletes all messages for a given persistenceId that match the given predicate
   * @param persistenceId
   * @param p
   * @return
   */
  def deleteMessages(persistenceId: String)(p : PersistentRepr => Boolean) =
    mm.get(persistenceId) match {
      case None =>
      case Some(m) => mm += (persistenceId -> m.filterNot(p))
    }

  /** Updates messages for a given persistenceId using the supplied function
    *
    * @param persistenceId
    * @param p
    * @return
    */
  def updateMessages(persistenceId: String)(p : PersistentRepr => PersistentRepr) =
    mm.get(persistenceId) match {
      case None =>
      case Some(m) => mm += (persistenceId -> m.map(p))
    }
}

/**
 * Adds some journal specific logic on top of the messages store
 */
trait InMemoryJournalBase extends InMemoryMessageStore {

  def doHardDelete(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long) =
    deleteMessages(persistenceId)(p => p.sequenceNr >= fromSequenceNr && p.sequenceNr <= toSequenceNr)

  def doSoftDelete(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long) =
    updateMessages(persistenceId)(p => PersistentRepr(p.payload, p.sequenceNr, p.persistenceId, (p.sequenceNr >= fromSequenceNr && p.sequenceNr <= toSequenceNr), p.redeliveries, p.confirms, p.confirmable, p.confirmMessage, p.confirmTarget, p.sender))

  def doUpdateConfirmations(persistenceId: String, messageSeqNr: Long, channelId: String) =
    updateMessages(persistenceId)(p =>
      if (messageSeqNr == p.sequenceNr) PersistentRepr(p.payload, p.sequenceNr, p.persistenceId, p.deleted, p.redeliveries, p.confirms ++ Seq(channelId), p.confirmable, p.confirmMessage, p.confirmTarget, p.sender)
      else p)

}

/**
 * Implementation of AsyncWriteJournal backed by the InMemoryMessage store
 */
class InMemoryJournal extends InMemoryJournalBase with AsyncWriteJournal with AsyncRecovery with ActorLogging {

  override def asyncWriteMessages(messages: Seq[PersistentRepr]): Future[Unit] = Future { addMessages(messages) }

  override def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long, permanent: Boolean): Future[Unit] =
    Future[Unit] {
      if (permanent) doHardDelete(persistenceId, 0L, toSequenceNr)
      else doSoftDelete(persistenceId, 0L, toSequenceNr)
  }

  override def asyncReplayMessages(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long)(replayCallback: (PersistentRepr) => Unit): Future[Unit] =
    Future[Unit] {
      getMessages(persistenceId, fromSequenceNr, toSequenceNr, max).foreach(m => replayCallback(m))
    }

  override def asyncReadHighestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long] =
    Future[Long] {
      getHighestSeq(persistenceId, fromSequenceNr)
    }

  @scala.deprecated("writeConfirmations will be removed, since Channels will be removed.")
  override def asyncWriteConfirmations(confirmations: Seq[PersistentConfirmation]): Future[Unit] = Future {
    confirmations.foreach(c => doUpdateConfirmations(c.persistenceId, c.sequenceNr, c.channelId))
  }

  @scala.deprecated("asyncDeleteMessages will be removed.")
  override def asyncDeleteMessages(messageIds: Seq[PersistentId], permanent: Boolean): Future[Unit] = Future {
    messageIds.foreach(p =>
      if (permanent) doHardDelete(p.persistenceId, p.sequenceNr, p.sequenceNr)
      else doSoftDelete(p.persistenceId, p.sequenceNr, p.sequenceNr))
  }

}

class SharedInMemoryMessageStore extends Actor with InMemoryJournalBase {
  import AsyncWriteTarget._

  override def receive: Receive = {
    case WriteMessages(messages) => sender() ! addMessages(messages)
    case WriteConfirmations(confirmations) => sender() ! confirmations.foreach(c => doUpdateConfirmations(c.persistenceId, c.sequenceNr, c.channelId))
    case DeleteMessages(messageIds, true) => sender() ! messageIds.foreach(m => doHardDelete(m.persistenceId, m.sequenceNr, m.sequenceNr))
    case DeleteMessages(messageIds, false) => sender() ! messageIds.foreach(m => doSoftDelete(m.persistenceId, m.sequenceNr, m.sequenceNr))
    case DeleteMessagesTo(persistenceId, toSeqNr, true) => sender ! doHardDelete(persistenceId, 0L, toSeqNr)
    case DeleteMessagesTo(persistenceId, toSeqNr, false) => sender ! doSoftDelete(persistenceId, 0L, toSeqNr)
    case ReadHighestSequenceNr(persistenceId, fromSeqNr) => sender ! getHighestSeq(persistenceId, fromSeqNr)
    case ReplayMessages(persistenceId, fromSeqNr, toSeqNr, max) =>
      Try (getMessages(persistenceId, fromSeqNr, toSeqNr, max).foreach(m => sender ! m)) match {
        case Success(max) => sender() ! ReplaySuccess
        case Failure(cause) => sender() ! ReplayFailure(cause)
      }
  }
}


class SharedInMemoryJournal extends AsyncWriteProxy {
  override implicit def timeout: Timeout = 5 seconds //TODO: make configurable
}

object SharedInMemoryJournal {
  def setStore(store: ActorRef, system: ActorSystem): Unit =
    Persistence(system).journalFor(null) ! AsyncWriteProxy.SetStore(store)
}