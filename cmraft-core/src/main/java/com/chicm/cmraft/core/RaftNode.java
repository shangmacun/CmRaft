/**
* Copyright 2014 The CmRaft Project
*
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at:
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
* WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
* License for the specific language governing permissions and limitations
* under the License.
*/

package com.chicm.cmraft.core;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.lang.math.RandomUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.chicm.cmraft.common.CmRaftConfiguration;
import com.chicm.cmraft.common.Configuration;
import com.chicm.cmraft.common.ServerInfo;
import com.chicm.cmraft.log.DefaultRaftLog;
import com.chicm.cmraft.log.RaftLog;
import com.chicm.cmraft.rpc.RpcServer;

/**
 * This class represents a Raft node in a cluster. This class
 * maintains a state machine, and implements the leader election
 * process, once a node is elected as leader, it handles user 
 * requests and AppendEntries RPCs, which is implemented in DefaultRaftLog
 * class.
 *  
 * Rule of Raft servers:
 * All Servers:
 * If commitIndex > lastApplied: increment lastApplied, apply
 * log[lastApplied] to state machine 
 * If RPC request or response contains term T > currentTerm:
 * set currentTerm = T, convert to follower 
 * 
 * Followers :
 * Respond to RPCs from candidates and leaders
 * If election timeout elapses without receiving AppendEntries
 * RPC from current leader or granting vote to candidate:
 * convert to candidate

 * Candidates :
 * On conversion to candidate, start election:
 * Increment currentTerm
 * Vote for self
 * Reset election timer
 * Send RequestVote RPCs to all other servers
 * If votes received from majority of servers: become leader
 * If AppendEntries RPC received from new leader: convert to
 * follower
 * If election timeout elapses: start new election

 * Leaders:
 * Upon election: send initial empty AppendEntries RPCs
 * (heartbeat) to each server; repeat during idle periods to
 * prevent election timeouts 
 * If command received from client: append entry to local log,
 * respond after entry applied to state machine
 * If last log index >= nextIndex for a follower: send
 * AppendEntries RPC with log entries starting at nextIndex
 * If successful: update nextIndex and matchIndex for
 * follower 
 * If AppendEntries fails because of log inconsistency:
 * decrement nextIndex and retry 
 * If there exists an N such that N > commitIndex, a majority
 * of matchIndex[i] >= N, and log[N].term == currentTerm:
 * set commitIndex = N 
 * 
 *
 * @author chicm
 *
 */
public class RaftNode {
  static final Log LOG = LogFactory.getLog(RaftNode.class);
  private Configuration conf = null;
  private StateMachine fsm = null;
  private RpcServer rpcServer = null;
  private NodeConnectionManager nodeConnectionManager = null;
  private RaftNodeTimer timer = null;
  private TimeoutListener timeoutListener = new TimeoutHandler();
  private RaftStateChangeListener stateChangeListener = new RaftStateChangeListenerImpl();
  private RaftRpcService raftService = null;
  private RaftLog raftLog= null;
  private ClusterMemberManager memberManager = null;

  //private ServerInfo serverInfo = null;
  private ServerInfo currentLeader = null;
  
  //persistent state for all servers
  //need to reset votedFor to null every time increasing currentTerm.
  private volatile ServerInfo votedFor = null;
  private volatile AtomicLong currentTerm = new AtomicLong(0);
  private volatile AtomicInteger voteCounter = new AtomicInteger(0);

  public RaftNode(Configuration conf) {
    this.conf = conf;
    memberManager = new ClusterMemberManager(conf);
    //serverInfo = ServerInfo.parseFromString(conf.getString("raft.server.local"));
    raftService = RaftRpcService.create(this, conf);
    raftLog= new DefaultRaftLog(this, conf);
    //initialize the term value to be the saved term of last run
    currentTerm.set(raftLog.getLogTerm(raftLog.getCommitIndex()));
    fsm = new StateMachine(stateChangeListener);
    rpcServer = new RpcServer(conf, raftService, getServerInfo());
    nodeConnectionManager = new NodeConnectionManager(conf, this);
    rpcServer.startRpcServer();
    timer = RaftNodeTimer.create(getName()+ "-timeout-worker", getElectionTimeout(), timeoutListener);
    
    LOG.info(String.format("%s initialized", getName()));
  }
  
  public long getCurrentTerm() {
    return currentTerm.get();
  }
  
  public ServerInfo getServerInfo() {
    return memberManager.getLocalServer();
  }
  
  public Set<ServerInfo> getRemoteServers() {
    return memberManager.getRemoteServers();
  }
  
  private void setCurrentLeader(ServerInfo newLeader) {
    this.currentLeader = newLeader;
  }
  public ServerInfo getCurrentLeader() {
    return currentLeader;
  }
  
  public RaftLog getRaftLog() {
    return this.raftLog;
  }
  
  public RaftRpcService getRaftService() {
    return raftService;
  }
  
  public NodeConnectionManager getNodeConnectionManager() {
    return this.nodeConnectionManager;
  }
  
  private int getElectionTimeout() {
    int confTimeout = conf.getInt("raft.election.timeout");
    int r = RandomUtils.nextInt(confTimeout);
    return confTimeout + r;    
  }
  
  public String getName() {
    return "RaftNode" + getServerInfo().toString();
  }
  
  public int getTotalServerNumbers () {
    return nodeConnectionManager.getRemoteServers().size() + 1;
  }
  
  public void resetTimer() {
    if(timer != null) { 
      timer.reset();
    } else {
      LOG.error(getName() + ":resetTimer ERROR: timeoutWork==null");
    }
  }
  
  public boolean isLeader() {
    return fsm.getState().equals(State.LEADER);
  }
  
  public State getState() {
    return fsm.getState();
  }
  
  //For testing only
  public void kill() {
    timer.stop();
  }
  
  public void increaseTerm() {
    this.currentTerm.getAndIncrement();
    this.votedFor = null;
    this.voteCounter.set(0);
  }
   
  public void checkRpcTerm(ServerInfo leader, long term) {
    if(term > getCurrentTerm()) {
      discoverHigherTerm(leader, term);
    }
  }
  
  public synchronized void discoverHigherTerm(ServerInfo remoteServer, long newTerm) {
    if(newTerm <= getCurrentTerm())
      return;
    
    LOG.info(String.format("%s discover higher term[%s](%d), local term:%d", 
      getName(), remoteServer, newTerm, getCurrentTerm()));
    
    currentTerm.set(newTerm);
    votedFor = null;
    voteCounter.set(0);
    fsm.discoverHigherTerm();
  }
  
  public synchronized void discoverLeader(ServerInfo leader, long term) {
    if(term < getCurrentTerm()) {
      return;
    }
    LOG.debug(getName() + " discover leader, leader term:" + leader + ":" + term + ", local term:" + getCurrentTerm());
    setCurrentLeader(leader);
    if(term > getCurrentTerm()) {
      currentTerm.set(term);
      votedFor = null;
      voteCounter.set(0);
    }
    fsm.discoverLeader();
  }
  
  //Received one vote from a follower
  //This method need to be thread safe, otherwise it should be synchronized.
  // currently it is thread safe, be careful to modify it.
  public void voteReceived(ServerInfo server, long term) {
    if(fsm.getState() != State.CANDIDATE)
      return;
    voteCounter.incrementAndGet();
    
    LOG.info(getName() + "vote received from: " + server + " votecounter:" + voteCounter.get());
    
    if(voteCounter.get() > getTotalServerNumbers()/2) {
      LOG.info(String.format("%s: RECEIVED MAJORITY VOTES(%d/%d), term(%d)", 
        getName(), voteCounter.get(), getTotalServerNumbers(), getCurrentTerm()));
      voteCounter.set(0);
      fsm.voteReceived();
    }
  }
  
  private void voteMySelf() {
    LOG.debug(getName() + ": VOTE MYSELF");
    if( voteRequest(getServerInfo(), getCurrentTerm(), 
      raftLog.getLastApplied(), raftLog.getLastLogTerm())) {
      voteReceived(getServerInfo(), getCurrentTerm());
    } else {
      LOG.info("voteMySelf failed!");
    }
  }
  
  /**
   * For follower, handle voteRequest RPC from candidate
   * @param candidate candidate server info
   * @param term term of candidate
   * @param lastLogIndex candidate's last log index
   * @param lastLogTerm  candidate's last log term
   * @return true if vote granted, otherwise false
   */
  public synchronized boolean voteRequest(ServerInfo candidate, long term, long lastLogIndex, long lastLogTerm) {
    boolean ret = false;
    if(term < getCurrentTerm())
      return ret;
    checkRpcTerm(candidate, term);
    
    if(isLeader() && term == getCurrentTerm()) {
      return false;
    }
    
    if(candidate.equals(votedFor)) {
      //for debug:
      Exception e = new Exception("already voted" + votedFor);
      e.printStackTrace(System.out);
    }
    
    if (votedFor == null || votedFor.equals(candidate)) {
      votedFor = candidate;
      ret = true;
      LOG.info(getName() + "voted for: " + candidate.getHost() + ":" + candidate.getPort());
      //reset election timeout if voted for other candidate as a follower:
      if(!candidate.equals(this.getServerInfo())) {
        restartTimer();
      }
    } else {
      LOG.info(getName() + "vote request rejected: " + candidate.getHost() + ":" + candidate.getPort());
    }
    
    return ret;
  }
  
  private void restartTimer() {
    if(timer == null) {
      LOG.error("restartTimer ERROR, timer == null");
      return;
    }
    
    int timeout = -1;
    switch(getState()) {
      case FOLLOWER:
      case CANDIDATE:
        timeout = getElectionTimeout();
        break;
      case LEADER:
        timeout = conf.getInt("raft.heartbeat.interval");
    }
    
    timer.reset(timeout);
  }
  
  public void testHearBeat() {
    nodeConnectionManager.beatHeart(getCurrentTerm(), getServerInfo(), raftLog.getCommitIndex(),
      raftLog.getLastApplied(), raftLog.getLastLogTerm());
  }
  
  private class RaftStateChangeListenerImpl implements RaftStateChangeListener {
    @Override
    public void stateChange(State oldState, State newState) {
      LOG.info(String.format("%s state change: %s=>%s", getName(), oldState, newState));
      
      //restart timer when state change.
      restartTimer();
      raftLog.stateChange(oldState, newState);
      
      switch(newState) {
        case FOLLOWER:
          break;
        case CANDIDATE:
          //start up a new election term when becoming candidate
          //increaseTerm(); //Term will be increased every timeout, so we do not need to increase term here
          break;
        case LEADER:
          setCurrentLeader(getServerInfo());
          //send heartbeat right away after becoming leader, then send out heartbeat every timeout 
          nodeConnectionManager.beatHeart(getCurrentTerm(), getServerInfo(), raftLog.getCommitIndex(),
            raftLog.getLastApplied(), raftLog.getLastLogTerm());
          break;
      }
    }
  }
  
  private class TimeoutHandler implements TimeoutListener, Runnable {
    @Override
    public void timeout() {
      Thread t = new Thread(new TimeoutHandler());
      t.setName(getName() + "-TimeoutHandler"); 
      t.setDaemon(true);
      t.start();
    }
    
    @Override
    public void run() {
      LOG.debug(getName() + " state:" + fsm.getState() + " timeout!!");
      //perform state change
      fsm.electionTimeout();
      
      //do initialization after state change
      if(fsm.getState() == State.LEADER) {
        //leader send heartbeat to all servers every timeout
        nodeConnectionManager.beatHeart(getCurrentTerm(), getServerInfo(), raftLog.getCommitIndex(),
          raftLog.getLastApplied(), raftLog.getLastLogTerm());
        
      } else if(fsm.getState() == State.CANDIDATE) {
        //every timeout period, candidates start up new election
        setCurrentLeader(null);
        increaseTerm();
        voteMySelf();
        nodeConnectionManager.collectVote(currentTerm.get(), raftLog.getLastApplied(), raftLog.getLastLogTerm());
      } else if( fsm.getState() == State.FOLLOWER ) {
        
      }
    }
  }
  
  public static void main(String[] args) {
    Configuration conf = CmRaftConfiguration.create();
    RaftNode node = new RaftNode(conf);
  }
}
