package com.interviewpartner.bot.repository;

import com.interviewpartner.bot.model.InterviewRequest;
import com.interviewpartner.bot.model.InterviewRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InterviewRequestRepository extends JpaRepository<InterviewRequest, Long> {
    Optional<InterviewRequest> findByIdAndStatus(Long id, InterviewRequestStatus status);
}

