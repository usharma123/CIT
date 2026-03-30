package com.cit.mocknet.repository;

import com.cit.mocknet.model.ParticipantVote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ParticipantVoteRepository extends JpaRepository<ParticipantVote, Long> {
    List<ParticipantVote> findByTransactionId(String transactionId);
}
