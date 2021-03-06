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

import akka.persistence.JournalProtocol.{ReplayMessages, ReplayMessagesSuccess}
import akka.persistence.journal.{JournalPerfSpec, JournalSpec}
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory

class InMemoryJournalSpec extends JournalSpec with JournalPerfSpec {
  lazy val config = ConfigFactory.parseString(
    """
      |akka {
      |  persistence {
      |    journal {
      |      plugin = "akka.persistence.inmem.journal"
      |    }
      |    snapshot-store {
      |      plugin = "akka.persistence.inmem.snapshot-store"
      |    }
      |  }
      |}
    """.stripMargin)

  "A journal" must {

    "truncate all messages" in {
      val receiverProbe = TestProbe()

      InMemoryMessageStore.truncate()
      journal ! ReplayMessages(1, Long.MaxValue, Long.MaxValue, pid, receiverProbe.ref)

      receiverProbe.expectMsg(ReplayMessagesSuccess)
    }
  }

}


