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

import akka.persistence.SnapshotProtocol.{LoadSnapshot, LoadSnapshotResult}
import akka.persistence.SnapshotSelectionCriteria
import akka.persistence.snapshot.SnapshotStoreSpec
import akka.testkit.TestProbe
import com.typesafe.config.{Config, ConfigFactory}

class InMemorySnapshotSpec extends SnapshotStoreSpec {
  override lazy val config: Config = ConfigFactory.parseString(
    """
      |akka.persistence.snapshot-store.plugin = "akka.persistence.inmem.snapshot-store"
      |akka.persistence.journal.plugin = "akka.persistence.inmem.journal"
    """.stripMargin)


  override protected def beforeEach(): Unit = {
    InMemorySnapshotStore.truncate()
    super.beforeEach()
  }

  "A snapshot store" must {

    "not load a snapshot given store is truncated" in {
      val senderProbe = TestProbe()

      InMemorySnapshotStore.truncate()

      snapshotStore.tell(LoadSnapshot(pid, SnapshotSelectionCriteria.Latest, Long.MaxValue), senderProbe.ref)
      senderProbe.expectMsg(LoadSnapshotResult(None, Long.MaxValue))
    }
  }

}
