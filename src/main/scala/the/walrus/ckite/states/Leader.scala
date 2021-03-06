package the.walrus.ckite.states

import java.util.concurrent.Executors
import java.util.concurrent.Future
import scala.util.Try
import the.walrus.ckite.Cluster
import the.walrus.ckite.rpc.Command
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import scala.collection.JavaConversions._
import the.walrus.ckite.executions.Executions
import the.walrus.ckite.rpc.RequestVoteResponse
import the.walrus.ckite.util.Logging
import the.walrus.ckite.rpc.AppendEntriesResponse
import the.walrus.ckite.rpc.RequestVote
import the.walrus.ckite.rpc.AppendEntries
import the.walrus.ckite.rpc.LogEntry
import the.walrus.ckite.RLog
import the.walrus.ckite.executions.Executions
import the.walrus.ckite.executions.ExpectedResultFilter
import java.lang.Boolean
import the.walrus.ckite.Member
import the.walrus.ckite.rpc.EnterJointConsensus
import the.walrus.ckite.rpc.MajorityJointConsensus
import the.walrus.ckite.rpc.LeaveJointConsensus

/**
 * 	•! Initialize nextIndex for each to last log index + 1
 * •! Send initial empty AppendEntries RPCs (heartbeat) to each
 * follower; repeat during idle periods to prevent election
 * timeouts (§5.2)
 * •! Accept commands from clients, append new entries to local
 * log (§5.3)
 * •! Whenever last log index ! nextIndex for a follower, send
 * AppendEntries RPC with log entries starting at nextIndex,
 * update nextIndex if successful (§5.3)
 * •! If AppendEntries fails because of log inconsistency,
 * decrement nextIndex and retry (§5.3)
 * •! Mark entries committed if stored on a majority of servers
 * and some entry from current term is stored on a majority of
 * servers. Apply newly committed entries to state machine.
 * •! Step down if currentTerm changes (§5.5)
 */
case object Leader extends State {

  override def begin(term: Int)(implicit cluster: Cluster) = {
    if (term < cluster.local.term) {
      LOG.debug(s"Cant be a Leader of term $term. Current term is ${cluster.local.term}")
      cluster.local.becomeFollower(cluster.local.term)
    } else {
      cluster.updateLeader(cluster.local.id)
      resetLastLog
      resetNextIndexes
      Heartbeater start term
      LOG.info(s"Start being Leader")
    }
  }

  private def resetLastLog(implicit cluster: Cluster) = RLog.resetLastLog()

  private def resetNextIndexes(implicit cluster: Cluster) = {
    val nextIndex = RLog.lastLog.intValue() + 1
    cluster.membership.allMembersBut(cluster.local).foreach { member => member.setNextLogIndex(nextIndex) }
  }

  override def stop(implicit cluster: Cluster) = {
    LOG.info("Stop being Leader")
    cluster.setNoLeader
    Heartbeater stop
  }

  override def on(command: Command)(implicit cluster: Cluster) =  {
    val logEntry = LogEntry(cluster.local.term, RLog.nextLogIndex, command)
    RLog.append(List(logEntry))
    LOG.info(s"Replicating log entry $logEntry")
    val replicationAcks = if (cluster.hasRemoteMembers) {
      val acks = Replicator.replicate(appendEntriesFor(logEntry))
      LOG.info(s"Got replication acks from $acks")
      acks
    } else  {
    	LOG.info(s"No member to replicate")
        Seq()
    }
    if (cluster.reachMajority(replicationAcks :+ cluster.local)) {
      RLog commit logEntry
    } else {
      LOG.info("Uncommited entry due to no majority")
    }
  }
  
  private def appendEntriesFor(logEntry: LogEntry)(implicit cluster: Cluster) = {
    val previousLogEntry = RLog.getPreviousLogEntry(logEntry)
    previousLogEntry match {
      case None => AppendEntries(cluster.local.term, cluster.local.id, RLog.getCommitIndex, entries = List(logEntry))
      case Some(entry) => AppendEntries(cluster.local.term, cluster.local.id, RLog.getCommitIndex, entry.index, entry.term, List(logEntry))
    }
  }

  override def on(appendEntries: AppendEntries)(implicit cluster: Cluster): AppendEntriesResponse = {
    if (appendEntries.term < cluster.local.term) {
      AppendEntriesResponse(cluster.local.term, false)
    } else {
      stepDown(Some(appendEntries.leaderId), appendEntries.term)
      cluster.local on appendEntries
    }
  }

  override def on(requestVote: RequestVote)(implicit cluster: Cluster): RequestVoteResponse = {
    if (requestVote.term <= cluster.local.term) {
      RequestVoteResponse(cluster.local.term, false)
    } else {
      stepDown(None, requestVote.term)
      cluster.local on requestVote 
    }
  }
  
  override def on(jointConsensusCommited: MajorityJointConsensus)(implicit cluster: Cluster) = {
	 LOG.info(s"Sending LeaveJointConsensus")
     cluster.on(LeaveJointConsensus(jointConsensusCommited.newBindings))
  }
  
}

object Replicator extends Logging {

  val Name = "Replicator"
  val ReplicateTimeout = 8000 //TODO: to be configurable
  val executor = Executors.newFixedThreadPool(50) //TODO: to be configurable
  
  //replicate and wait for a majority of acks. 
  def replicate(appendEntries: AppendEntries)(implicit cluster: Cluster): Seq[Member] = {
    val execution = Executions.newExecution().withExecutor(executor)
    cluster.membership.allMembersBut(cluster.local).foreach { member =>
      execution.withTask(new Callable[(Member, Boolean)] {
        override def call(): (Member, Boolean) = {
          val originalName = Thread.currentThread().getName()
          Thread.currentThread().setName(s"$Name-$member")
          val result:(Member, Boolean) = (member, member replicate appendEntries)
          Thread.currentThread().setName(originalName)
          result
        }
      })
    }
    val expectedResults = cluster.membership.majoritiesCount
    LOG.debug(s"Waiting for $expectedResults majority consisting of ${cluster.membership.majoritiesMap} until $ReplicateTimeout ms")
    val rawResults = execution.withTimeout(ReplicateTimeout, TimeUnit.MILLISECONDS)
    						  .withExpectedResults(expectedResults, new MajoritiesExpected(cluster)).execute[(Member,Boolean)]()
    val results: Iterable[(Member,Boolean)] = rawResults
    val mapres = results.filter { result => result._2 }.map { result => result._1 }
    mapres.toSeq
  }

}

class MajoritiesExpected(cluster: Cluster) extends ExpectedResultFilter {
  
  val majorities: java.util.Map[Seq[Member], Int] = cluster.membership.majoritiesMap
  
  override def matches(x: Any) = {
    val memberAck = x.asInstanceOf[(Member, Boolean)]
    
    val it = majorities.entrySet().iterator()
    
    while(it.hasNext()) {
        val entry = it.next()
         val members = entry.getKey()
        if (memberAck._2 && members.contains(memberAck._1)) {
             entry.setValue(entry.getValue() - 1)
        }
    }
    
    majorities.entrySet().filter { entry =>
        entry.getValue() <= 1 //the remaining one is the local member who counts for majority
    }.size
  }
}

object Heartbeater extends Logging {

  val Name = "Heartbeater"
  val executor = Executors.newFixedThreadPool(1)
  val heartbeatFuture = new AtomicReference[Future[_]]()

  def start(term: Int)(implicit cluster: Cluster) = {
    heartbeatFuture.set(executor.submit(new Runnable() {
      override def run() = {
        try {
          cluster updateContextInfo
          
          Thread.currentThread().setName(Name)
          do {
            cluster.broadcastHeartbeats(term)
            Thread.sleep(cluster.configuration.heartbeatsInterval)
          } while (true)
        } catch { case e: Exception => LOG.debug("Heartbeats interrupted") }
      }
    }))
  }

  def stop() = {
    val future = heartbeatFuture.get()
    if (future != null) future.cancel(true)
  }
}