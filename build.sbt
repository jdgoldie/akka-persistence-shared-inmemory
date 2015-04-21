//
//  Copyright 2014 Joshua Goldie
//
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
//
//  http://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.
//

import com.typesafe.sbt.SbtMultiJvm
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.MultiJvm
import sbt.Keys._
import bintray.AttrMap
import bintray._
import scoverage.ScoverageSbtPlugin._

lazy val root = (project in file("."))
  .settings(name := "akka-persistence-shared-inmemory")
  .settings(version := "1.0.16")
  .settings(scalaVersion := "2.11.5")
  .settings(organization := "com.github.jdgoldie")
  .settings(crossScalaVersions := Seq("2.11.5","2.10.4"))
  .settings(licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0")))
  .settings(bintrayPublishSettings:_*)
  .settings(publishMavenStyle := true)
  .settings(bintray.Keys.repository in bintray.Keys.bintray := "maven")
  .settings(bintray.Keys.bintrayOrganization in bintray.Keys.bintray := None)
  .settings(libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-persistence-experimental"      % "2.3.9" % "compile",
  "com.typesafe.akka" %% "akka-actor"                         % "2.3.9" % "compile",
  "com.typesafe.akka" %% "akka-remote"                        % "2.3.9" % "test",
  "com.typesafe.akka" %% "akka-testkit"                       % "2.3.9" % "test",
  "com.typesafe.akka" %% "akka-persistence-tck-experimental"  % "2.3.9" % "test",
  "org.scalatest"     %% "scalatest"                          % "2.1.4" % "test",
  "com.typesafe.akka" %% "akka-multi-node-testkit"            % "2.3.9" % "test",
  "org.scoverage"     %% "scalac-scoverage-plugin"            % "1.0.1" % "test"))
  //MultiJvm settings sourced from the activator sample
  .settings(SbtMultiJvm.multiJvmSettings: _*)
  .settings(compile in MultiJvm <<= (compile in MultiJvm) triggeredBy (compile in Test))
  .settings(dependencyClasspath in MultiJvm <++= dependencyClasspath in Test)
  .settings(unmanagedSourceDirectories in MultiJvm += baseDirectory.value / "src" / "multi-jvm")
  // make sure that MultiJvm tests are executed by the default test target,
  // and combine the results from ordinary test and multi-jvm tests
  .settings(executeTests in Test <<= (executeTests in Test, executeTests in MultiJvm) map {
    case (testResults, multiNodeResults) =>
      val overall =
        if (testResults.overall.id < multiNodeResults.overall.id)
          multiNodeResults.overall
        else
          testResults.overall
      Tests.Output(overall,
        testResults.events ++ multiNodeResults.events,
        testResults.summaries ++ multiNodeResults.summaries)}) configs(MultiJvm)
