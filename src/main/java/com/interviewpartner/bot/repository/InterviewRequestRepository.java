package com.interviewpartner.bot.repository;

import com.interviewpartner.bot.model.InterviewRequest;
import com.interviewpartner.bot.model.InterviewRequestStatus;
import com.interviewpartner.bot.model.InterviewFormat;
import com.interviewpartner.bot.model.Language;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface InterviewRequestRepository extends JpaRepository<InterviewRequest, Long> {
    Optional<InterviewRequest> findByIdAndStatus(Long id, InterviewRequestStatus status);

    @Query("""
            select r from InterviewRequest r
            join fetch r.candidate
            join fetch r.interviewer
            where (r.candidate.id = :userId or r.interviewer.id = :userId)
              and (:status is null or r.status = :status)
            order by r.dateTime desc
            """)
    List<InterviewRequest> findByUserIdAndOptionalStatus(@Param("userId") Long userId,
                                                         @Param("status") InterviewRequestStatus status);

    @Query("""
            select r from InterviewRequest r
            join fetch r.interviewer
            where r.candidate.id = r.interviewer.id
              and r.language = :language
              and r.status = com.interviewpartner.bot.model.InterviewRequestStatus.PENDING
              and r.dateTime > :now
              and r.interviewer.id <> :excludeUserId
            order by r.dateTime asc
            """)
    List<InterviewRequest> findOpenSoloRequests(@Param("language") Language language,
                                                @Param("excludeUserId") Long excludeUserId,
                                                @Param("now") LocalDateTime now);

    boolean existsByCandidateIdAndInterviewerIdAndLanguageAndFormatAndDateTimeAndDurationMinutesAndStatus(
            Long candidateId,
            Long interviewerId,
            Language language,
            InterviewFormat format,
            LocalDateTime dateTime,
            Integer durationMinutes,
            InterviewRequestStatus status
    );
}

