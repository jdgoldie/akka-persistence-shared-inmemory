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

package akka.persistence.inmem.snapshot

import akka.actor.{Actor, Stash, ActorLogging, ActorRef}
import akka.persistence.SnapshotProtocol.{LoadSnapshot, LoadSnapshotResult, SaveSnapshot, DeleteSnapshot, DeleteSnapshots}
import akka.persistence.{Persistence, SaveSnapshotSuccess, SaveSnapshotFailure, SnapshotMetadata, SnapshotSelectionCriteria, SelectedSnapshot}
import akka.persistence.snapshot.SnapshotStore
import akka.util.Timeout

import scala.collection.mutable.{HashMap, MultiMap, Set}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


/**
 * Supports SnapshotAPI implementations backed by a MultiMap
 */
class InMemorySnapshotStore extends SnapshotStore with ActorLogging {

  //SelectedSnapshot might not be the best name, but has meta + payload and is already defined
  val ss = new HashMap[String, Set[SelectedSnapshot]] with MultiMap[String, SelectedSnapshot]

  /**
   * Finds the youngest snapshot that matches selection criteria.
   * @param persistenceId
   * @param criteria
   * @return
   */
  private def findYoungestSnapshotByCriteria(persistenceId: String, criteria: SnapshotSelectionCriteria): Option[SelectedSnapshot] = {
    ss.get(persistenceId) match {
      case None => None
      case Some(snaps) => snaps.filter(s => criteria.matches(s.metadata)).toSeq.sortBy(s => s.metadata.sequenceNr).reverse.headOption
    }
  }

  /**
   * Internal delete implementation; removes all snapshots for persistenceId that match the given predicate
   * @param persistenceId
   * @param p
   * @return
   */
  def doDelete(persistenceId: String)(p: SelectedSnapshot => Boolean) = {
    ss.get(persistenceId) match {
      case None => None
      case Some(snaps) => ss += (persistenceId -> snaps.filterNot(p))
    }
  }

  // The plugin API

  def loadAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Option[SelectedSnapshot]] =
    Future {
      findYoungestSnapshotByCriteria(persistenceId, criteria)
    }

  def saveAsync(metadata: SnapshotMetadata, snapshot: Any): Future[Unit] =
    Future {
      ss.addBinding(metadata.persistenceId, SelectedSnapshot(metadata, snapshot))
    }

  def saved(metadata: SnapshotMetadata): Unit = {}

  def delete(metadata: SnapshotMetadata): Unit = doDelete(metadata.persistenceId)(s => s.metadata == metadata)

  def delete(persistenceId: String, criteria: SnapshotSelectionCriteria): Unit =
    doDelete(persistenceId)(s => criteria.matches(s.metadata))

}

/**
 * Proxy for a remote snapshot store
 */
class SharedInMemorySnapshotStore extends Actor with Stash with ActorLogging {

  private var store : ActorRef = _

  override def receive : Receive = {
    case SharedInMemorySnapshotStore.SetStore(ref) =>
      store = ref
      unstashAll()
      context.become(running)
    case _ => stash()
  }

  def running: Receive = {
    case l @ LoadSnapshot(persistenceId, criteria, toSequenceNr) =>
      store ! l
      context.become(awaitLoadResult(sender()))
    case s @ SaveSnapshot(metadata, snapshot) =>
      store ! s
      context.become(awaitSaveResult(sender()))
    case d @ DeleteSnapshot(metadata) =>
      store ! d
    case d @ DeleteSnapshots(persistenceId, criteria) =>
      store ! d
  }

  def awaitSaveResult(p : ActorRef) : Receive = {
    case s @ SaveSnapshotSuccess(_) =>
      p ! s
      unstashAll()
      context.become(running)
    case f @ SaveSnapshotFailure(_,_) =>
      p ! f
      unstashAll()
      context.become(running)
    case _ => stash()
  }

  def awaitLoadResult(p : ActorRef) : Receive = {
    case r @ LoadSnapshotResult(_,_) =>
      p ! r
      unstashAll()
      context.become(running)
    case _ => stash()
  }

}

object SharedInMemorySnapshotStore {
  final case class SetStore(ref: ActorRef)
}