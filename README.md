**Overview**

This project contains in-memory implementations of the journal and snapshot store for akka-persistence that are designed to work with multi-node and single-node unit tests.  Since all data is kept in memory structures, there are no side effects or cleanup between test runs.  These plugins are tested against `akka-persistence-tck-experimental` version 2.3.6

[![Build Status](https://travis-ci.org/jdgoldie/akka-persistence-shared-inmemory.svg)](https://travis-ci.org/jdgoldie/akka-persistence-shared-inmemory)

[![Coverage Status](https://img.shields.io/coveralls/jdgoldie/akka-persistence-shared-inmemory.svg)](https://coveralls.io/r/jdgoldie/akka-persistence-shared-inmemory)

**Adding to your project**

Add a resolver:

	defaultResolvers += ("jdgoldie at bintray" at "http://dl.bintray.com/jdgoldie/maven")

and the dependency:

	  "com.github.jdgoldie" %% "akka-persistence-shared-inmemory" % "1.0.15"

**Use in a single node test**

To use the journal and snapshot store in a basic unit test, add the following configuration to your `application.conf`:

      akka {
        persistence {
          journal {
            plugin = "akka.persistence.inmem.journal"
          }
          snapshot-store {
            plugin = "akka.persistence.inmem.snapshot-store"
          }
        }
      }


**Use in a multi-node test**

Using the journal and snapshot store in shared mode with a multi-node test requires a little more work.  The configuration is:

	persistence {
	  journal {
	    plugin = "akka.persistence.inmem.shared-journal"
	  }
	  snapshot-store {
	    plugin = "akka.persistence.inmem.shared-snapshot-store"
	  }
	}
	
These plugins are only proxies to the actual stores, however, and must be configured further.  In the `multi-jvm` tests, `ExampleMultiJvmTestSpec.scala` has an example of the additional configuration:

	Persistence(system)
	
	runOn(node1) {
	  SharedInMemoryJournal.setStore(system.actorOf(
	      Props[SharedInMemoryMessageStore], "journalStore"), system)
	}
	
	runOn(node2) {
	  SharedInMemoryJournal.setStore(getActorRef(
	      node(node1) / "user" / "journalStore").get, system)
	}
	
	
	
On *node1*, the journalStore actor is created and the plugin-proxy is given a reference.  On *node2*, the plugin-proxy is given a reference to the journalStore actor on *node1*.

The shared snapshot store is configured in a similar way.  An example can be found in `ExampleMultiJvmTestSpec.scala` as well:

	runOn(node1) {
	  Persistence(system).snapshotStoreFor(null) ! 
	      SharedInMemorySnapshotStore.SetStore(
	          system.actorOf(Props[InMemorySnapshotStore], "snapStore"))
	}
	
	runOn(node2) {
	  Persistence(system).snapshotStoreFor(null) ! 
	      SharedInMemorySnapshotStore.SetStore(
	          getActorRef(node(node1) / "user" / "snapStore").get)
	}
	
	
	
**Acknowledgements**

The design for the shared plugins is heavily based on the shared LevelDB journal in `akka-persistence`.  The shared journal test is ported from the shared LevelDB journal test from the same source.    

Thanks to [OneGeek](http://www.onegeek.com.au) for the [article](http://www.onegeek.com.au/scala/setting-up-travis-ci-for-scala)  on TravisCi-to-Bintray setup