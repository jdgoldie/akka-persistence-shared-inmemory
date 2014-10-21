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

import akka.actor.{Props, ActorRef}
import akka.persistence.{SnapshotSelectionCriteria, Persistence, SaveSnapshotSuccess, SnapshotMetadata}
import akka.persistence.SnapshotProtocol.{LoadSnapshotResult, LoadSnapshot, SaveSnapshot}
import akka.persistence.snapshot.SnapshotStoreSpec
import akka.testkit.TestProbe
import com.typesafe.config.{ConfigFactory, Config}
import scala.collection.immutable.Seq
import scala.concurrent.duration.{FiniteDuration, DurationInt}

class SharedInMemorySnapshotSpec extends SnapshotStoreSpec {

  override lazy val config: Config = ConfigFactory.parseString(
    """
      |akka.persistence.snapshot-store.plugin = "akka.persistence.inmem.shared-snapshot-store"
      |akka.persistence.journal.plugin = "akka.persistence.inmem.journal"
      |akka.test.single-expect-default=6000
    """.stripMargin)

  protected override def beforeAll(): Unit = {
    super.beforeAll()
    Persistence(system)
    extension.snapshotStoreFor(null) ! SharedInMemorySnapshotStore.SetStore(system.actorOf(Props[InMemorySnapshotStore], "snapStore"))
  }

}