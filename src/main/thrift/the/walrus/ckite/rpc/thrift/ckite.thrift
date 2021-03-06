namespace java the.walrus.ckite.rpc.thrift

struct LogEntryST {
	1: required i32 term;
	2: required i32 index;
	3: required binary command;
}

struct AppendEntriesST {
	1: required i32 term;
	2: required string leaderId; 
	3: optional i32 commitIndex = -1; 
	4: optional i32 prevLogIndex = -1;
	5: optional i32 prevLogTerm = -1;
	6: optional list<LogEntryST> entries;
}

struct AppendEntriesResponseST {
	1: required i32 term;
	2: required bool success;
}

struct RequestVoteST {
	1: required string memberId;
	2: required i32 term;
	3: optional i32 lastLogIndex = -1;
	4: optional i32 lastLogTerm = -1; 
}

struct RequestVoteResponseST {
	1: required i32 currentTerm;
	2: required bool granted;
}

service CKiteService {

	RequestVoteResponseST sendRequestVote(1:RequestVoteST requestVote);

	AppendEntriesResponseST sendAppendEntries(1:AppendEntriesST appendEntries);
	
	void forwardCommand(1:binary command);

}
