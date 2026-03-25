package com.cit.clsnet.netting.util;

import com.cit.clsnet.model.ParticipantVote;
import com.cit.clsnet.model.VoteStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class ParticipantVoteFactory {

    public ParticipantVote commitVote(String transactionId, String participantName, String reason) {
        return create(transactionId, participantName, VoteStatus.VOTE_COMMIT, reason);
    }

    public ParticipantVote abortVote(String transactionId, String participantName, String reason) {
        return create(transactionId, participantName, VoteStatus.VOTE_ABORT, reason);
    }

    private ParticipantVote create(String transactionId, String participantName, VoteStatus voteStatus, String reason) {
        ParticipantVote vote = new ParticipantVote();
        vote.setTransactionId(transactionId);
        vote.setParticipantName(participantName);
        vote.setVote(voteStatus);
        vote.setReason(reason);
        vote.setVotedAt(Instant.now());
        return vote;
    }
}
