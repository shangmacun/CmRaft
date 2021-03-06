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

import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.chicm.cmraft.common.Configuration;
import com.chicm.cmraft.common.ServerInfo;
import com.chicm.cmraft.protobuf.generated.RaftProtos.AppendEntriesRequest;
import com.chicm.cmraft.protobuf.generated.RaftProtos.AppendEntriesResponse;
import com.chicm.cmraft.protobuf.generated.RaftProtos.CollectVoteRequest;
import com.chicm.cmraft.protobuf.generated.RaftProtos.CollectVoteResponse;
import com.chicm.cmraft.protobuf.generated.RaftProtos.RaftLogEntry;
import com.chicm.cmraft.protobuf.generated.RaftProtos.ServerId;
import com.chicm.cmraft.rpc.RpcClient;
import com.google.common.base.Preconditions;
import com.google.protobuf.ServiceException;

/**
 * @author chicm
 *
 */
public class DefaultNodeConnection implements NodeConnection {
  static final Log LOG = LogFactory.getLog(DefaultNodeConnection.class);
  private Configuration conf;
  private RpcClient rpcClient;
  private ServerInfo remoteServer;
  
  public DefaultNodeConnection(Configuration conf, ServerInfo remoteServer) {
    this.conf = conf;
    this.remoteServer = remoteServer;
    rpcClient = new RpcClient(conf, remoteServer);
  }

  /* (non-Javadoc)
   * @see com.chicm.cmraft.core.NodeConnection#collectVote(com.chicm.cmraft.common.ServerInfo, long, long, long)
   */
  @Override
  public CollectVoteResponse collectVote(ServerInfo candidate, long term, long lastLogIndex,
      long lastLogTerm) throws Exception  {
        
    CollectVoteRequest.Builder builder = CollectVoteRequest.newBuilder();
    builder.setCandidateId(candidate.toServerId());
    builder.setTerm(term);
    builder.setLastLogIndex(lastLogIndex);
    builder.setLastLogTerm(lastLogTerm);
    
    return (CollectVoteResponse)(rpcClient.getStub().collectVote(null, builder.build()));
  }

  /* (non-Javadoc)
   * @see com.chicm.cmraft.core.NodeConnection#appendEntries(long, com.chicm.cmraft.common.ServerInfo, long, long, long, java.util.List)
   */
  @Override
  public AppendEntriesResponse appendEntries(long term, ServerInfo leaderId, long leaderCommit,
      long prevLogIndex, long prevLogTerm, List<RaftLogEntry> entries) throws ServiceException {

    Preconditions.checkNotNull(entries);
    
    AppendEntriesRequest.Builder builder = AppendEntriesRequest.newBuilder();
    builder.setTerm(term);
    builder.setLeaderId(leaderId.toServerId());
    builder.setLeaderCommit(leaderCommit);
    builder.setPrevLogIndex(prevLogIndex);
    builder.setPrevLogTerm(prevLogTerm);
    builder.addAllEntries(entries);
    
    try {
      LOG.debug(leaderId + "making appendEntries call to: " + getRemoteServer());
      AppendEntriesResponse response = rpcClient.getStub().appendEntries(null, builder.build());
      return response;
    } catch(Exception e) {
      LOG.error("exception", e);
      LOG.error("remote server:" + getRemoteServer());
      LOG.error("Log entries:" + entries.toString());
    }
    return null;
  }
  
  /* (non-Javadoc)
   * @see com.chicm.cmraft.core.NodeConnection#getRemoteServer()
   */
  @Override
  public ServerInfo getRemoteServer() {
    return this.remoteServer;
  }

  /* (non-Javadoc)
   * @see com.chicm.cmraft.core.NodeConnection#close()
   */
  @Override
  public void close() {
    rpcClient.close();
  }

}
