package the.walrus.ckite

import java.util.concurrent.atomic.AtomicInteger
import the.walrus.ckite.rpc.RequestVote
import java.util.concurrent.atomic.AtomicReference
import the.walrus.ckite.states.Follower
import the.walrus.ckite.rpc.Command
import the.walrus.ckite.states.Leader
import the.walrus.ckite.states.Candidate
import the.walrus.ckite.rpc.RequestVoteResponse
import the.walrus.ckite.util.Logging
import the.walrus.ckite.states.State
import the.walrus.ckite.rpc.AppendEntriesResponse
import the.walrus.ckite.rpc.AppendEntries
import the.walrus.ckite.rpc.Connector
import the.walrus.ckite.rpc.thrift.ThriftConnector
import java.net.ConnectException
import com.twitter.finagle.ChannelWriteException
import the.walrus.ckite.rpc.AppendEntriesResponse
import the.walrus.ckite.states.Starter
import the.walrus.ckite.rpc.EnterJointConsensus
import the.walrus.ckite.rpc.MajorityJointConsensus

class Member(val binding: String) extends Logging {

  val currentTerm = new AtomicInteger(0)
  val nextLogIndex = new AtomicInteger(1)
  val state = new AtomicReference[State](Starter)
  val connector: Connector = new ThriftConnector(binding)
  val votedFor = new AtomicReference[Option[String]]

  def id() = s"$binding"
  
  def on(appendEntries: AppendEntries)(implicit cluster: Cluster): AppendEntriesResponse = currentState on appendEntries

  def on(command: Command)(implicit cluster: Cluster) = currentState on command
  
  def on(jointConsensusCommited: MajorityJointConsensus)(implicit cluster: Cluster) = currentState on jointConsensusCommited
  
  def on(requestVote: RequestVote)(implicit cluster: Cluster): RequestVoteResponse = cluster.synchronized {
      if (requestVote.term < term) {
        LOG.debug(s"Rejecting vote to old candidate: ${requestVote}")
        RequestVoteResponse(term, false)
      } else {
        currentState on requestVote
      }
  }
  
  def sendHeartbeat(term: Int)(implicit cluster: Cluster) = synchronized {
    LOG.trace(s"Sending heartbeat to $id in term ${term}")
    val appendEntries = createAppendEntries(term)
    connector.send(this, appendEntries).map {
      appendEntriesResponse =>
        if (appendEntriesResponse.term > term) {
          LOG.debug(s"Detected a term ${appendEntriesResponse.term} higher than current term ${term}. Step down")
          cluster.local.currentState.stepDown(None, term)
        } else {
          if (!appendEntries.entries.isEmpty) {
            onAppendEntriesResponseUpdateNextLogIndex(appendEntries, appendEntriesResponse)
          }
        }
    }
  }

  private def createAppendEntries(termToSent: Int)(implicit cluster: Cluster): AppendEntries =  {
    val entryToPiggyBack = RLog.getLogEntry(nextLogIndex.intValue())
    entryToPiggyBack match {
      case None => AppendEntries(termToSent, cluster.local.id, RLog.getCommitIndex)
      case Some(entry) => {
        val entriesToPiggyBack = List(entry)
        val appendEntriesMessage = RLog.getPreviousLogEntry(entriesToPiggyBack(0)) match {
          case None => AppendEntries(termToSent, cluster.local.id, RLog.getCommitIndex, entries = entriesToPiggyBack)
          case Some(previousEntry) => AppendEntries(termToSent, cluster.local.id, RLog.getCommitIndex, previousEntry.index, previousEntry.term, entriesToPiggyBack)
        }
        LOG.trace(s"Piggybacking entry $entry to $id. Message is $appendEntriesMessage")
        appendEntriesMessage
      }
    }
  }
  
  def replicate(appendEntries: AppendEntries) =  { 
    LOG.info(s"Replicating to $id")
    synchronized {
    connector.send(this, appendEntries).map { replicationResponse =>
      onAppendEntriesResponseUpdateNextLogIndex(appendEntries, replicationResponse)
      replicationResponse.success
    }.recover {
      case ChannelWriteException(e:ConnectException)  =>
        LOG.debug(s"Can't connect to member $id")
        false
      case e: Exception =>
        LOG.error(s"Error replicating to $id: ${e.getMessage()}",e)
        false
    } get
  }
  }
  private def onAppendEntriesResponseUpdateNextLogIndex(appendEntries: AppendEntries, appendEntriesResponse: AppendEntriesResponse) = {
      if (appendEntriesResponse.success) {
        nextLogIndex.incrementAndGet()
      } else {
        val currentIndex = nextLogIndex.decrementAndGet()
        if (currentIndex == 0) nextLogIndex.set(1)
      }
      LOG.debug(s"Member $binding $appendEntriesResponse - NextLogIndex is $nextLogIndex")
  }
  
  def setNextLogIndex(index: Int) = nextLogIndex.set(index)

  def term(): Int = this.currentTerm.intValue()

  def updateTermIfNeeded(receivedTerm: Int)(implicit cluster: Cluster) = {
    if (receivedTerm > term) {
      LOG.debug(s"New term detected. Moving from ${term} to ${receivedTerm}.")
      votedFor.set(None)
      currentTerm.set(receivedTerm)
      cluster.updateContextInfo
    }
  }

  def incrementTerm(implicit cluster: Cluster) = {
    val term = currentTerm.incrementAndGet()
    cluster.updateContextInfo
    term
  }
  
  /* If the candidate receives no response for an RPC, it reissues the RPC repeatedly until a response arrives or the election concludes */
  def requestVote(implicit cluster: Cluster): Boolean = {
    LOG.debug(s"Requesting vote to $id")
    val lastLogEntry = RLog.getLastLogEntry()
    connector.send(this, lastLogEntry match {
      case None => RequestVote(cluster.local.id, cluster.local.term)
      case Some(entry) => RequestVote(cluster.local.id, cluster.local.term, entry.index, entry.term)
    }).map { voteResponse =>
      LOG.debug(s"Got Request vote response: $voteResponse")
      voteResponse.granted
    } recover {
      case ChannelWriteException(e:ConnectException)  =>
        LOG.debug(s"Can't connect to member $id")
        false
      case e: Exception => 
        LOG.error(s"Requesting vote: ${e.getMessage()}")
        false
    } get
  }
  
  def voteForMyself = votedFor.set(Some(id))

  def forwardCommand(command: Command) = connector.send(this, command)

  def becomeLeader(term: Int)(implicit cluster: Cluster) = become(Leader, term)

  def becomeCandidate(term: Int)(implicit cluster: Cluster) = become(Candidate, term)

  def becomeFollower(term: Int)(implicit cluster: Cluster) = become(Follower, term)

  private def become(newState: State, term: Int)(implicit cluster: Cluster) = {
    LOG.info(s"Transition from $state to $newState")
    currentState stop
    
    changeState(newState)
    
    currentState begin term
  }

  private def currentState = state.get()

  private def changeState(newState: State) = state.set(newState)

  override def toString() = id
  
}

