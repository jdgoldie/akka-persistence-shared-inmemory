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

package akka.persistence.inmem.sample

import akka.actor.{ActorLogging, Props}
import akka.persistence.serialization.Snapshot
import akka.persistence.{SnapshotMetadata, SaveSnapshotSuccess, SnapshotOffer, PersistentActor, Persistence}
import akka.persistence.inmem.journal.{SharedInMemoryJournal, SharedInMemoryMessageStore}
import akka.persistence.inmem.snapshot.{InMemorySnapshotStore, SharedInMemorySnapshotStore}
import akka.remote.testkit.MultiNodeConfig
import com.typesafe.config.ConfigFactory
import scala.concurrent.duration.DurationInt

object ExampleMultiJvmTestConfig extends MultiNodeConfig {

  val node1 = role("node1")
  val node2 = role("node2")

  commonConfig(ConfigFactory.parseString(
    """
      | akka {
      |
      |  loglevel = "ERROR"
      |  stdout-loglevel = "ERROR"
      |
      |  remote {
      |    log-remote-lifecycle-events = off
      |    netty.tcp {
      |      hostname = "127.0.0.1"
      |      port = 0
      |    }
      |  }
      |
      |  actor {
      |    debug {
      |      autoreceive=off
      |      receive=off
      |      lifecycle=off
      |      event-stream=off
      |    }
      |  }
      |
      |  persistence {
      |    journal {
      |      plugin = "akka.persistence.inmem.shared-journal"
      |    }
      |    snapshot-store {
      |      plugin = "akka.persistence.inmem.shared-snapshot-store"
      |    }
      |  }
      |}
      | """.stripMargin))

  testTransport(on=true)

}

class ExampleTestMultiJvmNode1 extends ExampleMuiltiJvmTestSpec
class ExampleTestMultiJvmNode2 extends ExampleMuiltiJvmTestSpec

class ExampleMuiltiJvmTestSpec extends ExampleMultiJvmBaseSpec(ExampleMultiJvmTestConfig) {

  import ExampleMultiJvmTestConfig._

  override def initialParticipants: Int = roles.size


  "Shared persistence stores" should "initialize the journal correctly" in {

    Persistence(system)

    // The journal actor on each node is an AsyncWriteProxy that does not store anything itself.  It needs
    // a reference to the actor that actually implements the journal operations on top of the in-memory
    // store.  We'll create this actor on node1 and set it on the journal actor
    runOn(node1) {
      SharedInMemoryJournal.setStore(system.actorOf(Props[SharedInMemoryMessageStore], "journalStore"), system)
    }

    enterBarrier("persistenceStarted")

    // On node2, we need a reference to the store actor running on node1.  We look up this reference
    // and set it on the journal actor.
    runOn(node2) {
      SharedInMemoryJournal.setStore(getActorRef(node(node1) / "user" / "journalStore").get, system)
    }

    enterBarrier("journalInitialized")

  }

  it should "initialize the shared snapshot store correctly" in {

    // Similar to the journal setup, the snapshot store is a proxy that delegates to the actual store implementation.
    // We create the store actor on node one and set it on the snapshot actor
    runOn(node1) {
      Persistence(system).snapshotStoreFor(null) ! SharedInMemorySnapshotStore.SetStore(system.actorOf(Props[InMemorySnapshotStore], "snapStore"))
    }

    enterBarrier("snapshotStoreStarted")

    // Similar to the journal setup, we lookup  a reference to the store actor and set it on the snapshot actor
    runOn(node2) {
      Persistence(system).snapshotStoreFor(null) ! SharedInMemorySnapshotStore.SetStore(getActorRef(node(node1) / "user" / "snapStore").get)
    }

    enterBarrier("snapshotsInitialized")

  }

  it should "correctly handle journal duties for both nodes" in {

    runOn(node1) {
      //Create the persistent actor on node1 and sent a Ping.  Expect to get back a 1 since this is the
      //first message.  Terminate the actor on this node.
      val pingActor = system.actorOf(Props(classOf[PingActor.PingActorWithJournal]), "PingActor-Node1")
      within(5 seconds) {
        pingActor ! PingActor.Ping
        expectMsgClass(classOf[PingActor.Pong])
      }.seq should be(1)
      terminateActor(pingActor)
      enterBarrier("node1Complete","node2Complete")
    }

    runOn(node2) {
      enterBarrier("node1Complete")
      //Once node1 is done with the persistent actor, attempt to recreate it on node2.  It should be recovered from
      //the journal so when the Ping is sent, expect to get back a 2.
      val pingActor = system.actorOf(Props(classOf[PingActor.PingActorWithJournal]), "PingActor-Node2")
      within(5 seconds) {
        pingActor ! PingActor.Ping
        expectMsgClass(classOf[PingActor.Pong])
      }.seq should be(2)
      terminateActor(pingActor)
      enterBarrier("node2Complete")
    }

  }

  it should "correctly handle snapshot duties for both nodes" in {

    runOn(node1) {
      //A repeat of the previous test with a actor that -only- recovers from snapshots.  Actor is created, a
      //Ping is sent and response verified.  Then the actor is told to record a snapshot and then is terminated.
      val pingActor = system.actorOf(Props(classOf[PingActor.PingActorWithSnapshot]), "PingActor-Node1")
      within(5 seconds) {
        pingActor ! PingActor.Ping
        expectMsg(PingActor.Pong(1))
      }
      within(5 seconds) {
        system.eventStream.subscribe(testActor, classOf[SaveSnapshotSuccess])
        pingActor ! Snapshot
        expectMsgClass(classOf[SaveSnapshotSuccess])
      }
      terminateActor(pingActor)
      enterBarrier("node1Complete","node2Complete")
    }

    runOn(node2) {
      enterBarrier("node1Complete")
      //The same approach as the previous test: recreate the actor on a different node and verify that the
      //state is recovered.  The difference is that this actor will only recover from snapshots.  See
      //below for the implementation.
      val pingActor = system.actorOf(Props(classOf[PingActor.PingActorWithSnapshot]), "PingActor-Node2")
      within(5 seconds) {
        pingActor ! PingActor.Ping
        expectMsgClass(classOf[PingActor.Pong])
      }.seq should be(2)
      terminateActor(pingActor)
      enterBarrier("node2Complete")
    }

  }

}

/**
 * A simple persistent actor that increments a counter and Pongs the value every time it gets a Ping message.
 * Note, that in the abstract, this actor does not implement receiveRecover.  See the implementations below
 * for an explanation.
 */
abstract class PingActor(val persistenceId: String) extends PersistentActor with ActorLogging {

  import PingActor._

  var state : Integer = 0

  override def receiveCommand: Receive = {
    case Ping =>
      state += 1
      persist(Pong(state))  { event => sender ! event}
    case Snapshot => saveSnapshot(state)
    case s @ SaveSnapshotSuccess(meta: SnapshotMetadata) => context.system.eventStream.publish(s)
  }
}

object PingActor {

  case object Ping
  case class Pong(seq: Integer)

  /**
   * An implementation of the actor with a receiveRecover that does not process offered Snapshots.  Used to test
   * recovery from the Journal.  If the journal does not correctly replay the messages,
   * the recovery, and by extension, the test case will fail.  We ignore snapshots to make sure this is a Journal-only
   * recovery
   */
  class PingActorWithJournal extends PingActor("PingActor-JournalTest") {
    self: PingActor =>

    override def receiveRecover: Receive = {
      case Pong(x: Integer) => state += 1
    }
  }

  /**
   * An implementation of the actor with a  receiveRecover that does not process persistent messages offered by the
   * Journal.  Used to test recovery from the Snapshot store.  By ignoring anything that may come
   * from the Journal, we can test that the Snapshot is correctly offered during recovery.
   * If the snapshot is not received, the actor will have incorrect state and the test will fail.
   * Ignoring journal messages ensures the actor cannot use them as a "fallback" during recovery
   */
  class PingActorWithSnapshot extends PingActor("PingActor-SnapshotTest") {
    self: PingActor =>

    override def receiveRecover: Receive = {
      case SnapshotOffer(meta: SnapshotMetadata, snapshot:Integer) => state = snapshot
    }
  }


}