package akka.persistence.inmem.sample

import akka.actor.{ActorPath, Identify, ActorIdentity, Terminated, ActorRef}
import akka.remote.testkit.{MultiNodeConfig, MultiNodeSpec, MultiNodeSpecCallbacks}
import akka.testkit.ImplicitSender
import org.scalatest.{FlatSpecLike, BeforeAndAfterAll, Matchers}

import scala.concurrent.duration.DurationInt

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

abstract class ExampleMultiJvmBaseSpec(val config: MultiNodeConfig)
  extends MultiNodeSpec(config)
  with MultiNodeSpecCallbacks
  with FlatSpecLike
  with Matchers
  with ImplicitSender
  with BeforeAndAfterAll {

  override def beforeAll() = multiNodeSpecBeforeAll()

  override def afterAll() = {
    multiNodeSpecAfterAll()
    system.shutdown()
    system.awaitTermination(10 seconds)
  }

  /**
   * Get an ActorRef for the given path
   *
   * @param actorPath String path for the actor
   * @return
   */
  def getActorRef(actorPath: String): Option[ActorRef] = {
    (within(5 seconds) {
      system.actorSelection(actorPath) ! Identify(None)
      expectMsgClass(classOf[ActorIdentity])
    }).ref
  }

  /**
   * Get an ActorRef by path using Identify message
   * @param actorPath ActorPath for the actor
   * @return
   */
  def getActorRef(actorPath: ActorPath): Option[ActorRef] = getActorRef(actorPath.toString)

  /**
   * Terminate an actor
   * @param ref
   * @return
   */
  def terminateActor(ref: ActorRef): Terminated = {
    val terminationMsg = within(5 seconds) {
      watch(ref)
      system.stop(ref)
      expectMsgClass(classOf[Terminated])
    }
    terminationMsg
  }

}