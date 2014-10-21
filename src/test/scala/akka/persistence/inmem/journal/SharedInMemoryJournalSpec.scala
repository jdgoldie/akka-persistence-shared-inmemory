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

import akka.actor._
import akka.persistence.{ RecoveryCompleted, PersistentActor}
import akka.testkit.{TestProbe, TestKit}
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike}


object SharedInMemoryJournalSpec {

  lazy val config = ConfigFactory.parseString(
    """
      |akka {
      |  actor {
      |    provider = "akka.remote.RemoteActorRefProvider"
      |  }
      |  persistence {
      |    journal {
      |      plugin = "akka.persistence.inmem.shared-journal"
      |    }
      |    snapshot-store {
      |      plugin = "akka.persistence.inmem.snapshot-store"
      |    }
      |  }
      |
      |  remote {
      |     enabled -transports =["akka.remote.netty.tcp"]
      |     netty.tcp {
      |       hostname = "127.0.0.1"
      |       port = 0
      |     }
      |  }
      |
      |  event-handlers = ["akka.testkit.TestEventListener"]
      |
      |  loglevel = ERROR
      |
      |  log-dead-letters = 0
      |  log-dead-letters-during-shutdown = off
      |  test.single-expect-default = 10 s
      |
      |}
    """.stripMargin)

  case object Ping
  case class Pong(seq: Integer)

  /**
   * Persistent actor that simply returns the next integer in sequence each time it
   * receives a Ping command
   * @param persistenceId
   */
  class PingActor(override val persistenceId: String, applicationActor: ActorRef) extends PersistentActor with ActorLogging {
    var state: Integer = 0

    val receiveRecover: Receive = {
      case Pong(x) => {
        state = x
        applicationActor ! Pong(x) //to verify message playback in test
      }
      case RecoveryCompleted => log.info("RecoveryComplete")
    }

    val receiveCommand: Receive =  {
      case Ping => {
        state += 1
        persist(Pong(state)) { event => applicationActor ! event}
      }
    }

  }

  /**
   * Application that drives the PingActor for testing purposes.
   * @param applicationName
   * @param testProbe
   * @param storePath
   */
  class PingApplication(applicationName: String, testProbe: ActorRef, storePath: ActorPath) extends Actor with ActorLogging {
    val pingActor = context.actorOf(Props(classOf[PingActor], computePersitenceId, context.self))

    def computePersitenceId = s"PingActor${applicationName.charAt(0)}"

    def receive = {
      //When this actor is started, it pings the shared store with Identify and sets up the journal
      case ActorIdentity(1, Some(store)) => SharedInMemoryJournal.setStore(store, context.system)
      case Ping => pingActor ! Ping
      case Pong(x) => testProbe ! s"$applicationName-$x"
    }

    override def preStart(): Unit = {
      context.system.actorSelection(storePath) ! Identify(1)
    }

  }

}

class SharedInMemoryJournalSpec extends TestKit(ActorSystem("SharedInMemorySpecSystem", SharedInMemoryJournalSpec.config)) with FlatSpecLike with BeforeAndAfterAll {
  import SharedInMemoryJournalSpec._

  val processorASystem = ActorSystem("processorA", system.settings.config)
  val processorBSystem = ActorSystem("processorB", system.settings.config)

  override protected def afterAll() = {
    shutdown(processorASystem)
    shutdown(processorBSystem)
    super.afterAll()
  }

  "A SharedInMemoryJournal" should "be shareable between actor systems" in {

    //Need one test probe for each system
    val processorAProbe = new TestProbe(processorASystem)
    val processorBProbe = new TestProbe(processorBSystem)

    //Set up the backing store for the journal
    system.actorOf(Props[SharedInMemoryMessageStore], "store")
    val storePath = RootActorPath(system.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress) / "user" / "store"

    val appA = processorASystem.actorOf(Props(classOf[PingApplication], "A1", processorAProbe.ref, storePath), "applicationA")
    val appB = processorBSystem.actorOf(Props(classOf[PingApplication], "B1", processorBProbe.ref, storePath), "applicationB")

    appA ! Ping
    appB ! Ping

    processorAProbe.expectMsg("A1-1")
    processorBProbe.expectMsg("B1-1")

    val appA2 = processorASystem.actorOf(Props(classOf[PingApplication], "A2", processorAProbe.ref, storePath), "applicationA2")
    val appB2 = processorBSystem.actorOf(Props(classOf[PingApplication], "B2", processorBProbe.ref, storePath), "applicationB2")

    appA2 ! Ping
    appB2 ! Ping

    processorAProbe.expectMsg("A2-1")
    processorAProbe.expectMsg("A2-2")
    processorBProbe.expectMsg("B2-1")
    processorBProbe.expectMsg("B2-2")

  }

}


