package com.chicm.cmraft.log;

import java.util.Collection;
import java.util.List;

import com.chicm.cmraft.common.ServerInfo;
import com.chicm.cmraft.core.State;

public interface RaftLog {
  void stateChange(State oldState, State newState);
  long getLogTerm(long index);
  long getCommitIndex();
  long getLastApplied();
  long getLastLogTerm();
  long getFlushedIndex();
  List<LogEntry> getLogEntries(long startIndex, long endIndex);
  long getFollowerMatchIndex(ServerInfo follower);
  
  boolean appendEntries(long term, ServerInfo leaderId, long leaderCommit,
      long prevLogIndex, long prevLogTerm, List<LogEntry> leaderEntries);
  void onAppendEntriesResponse(ServerInfo follower, long followerTerm, boolean success, 
      long followerLastApplied);
  
  boolean set(byte[] key, byte[] value);
  void delete(byte[] key);
  Collection<LogEntry> list(byte[] pattern);
}
