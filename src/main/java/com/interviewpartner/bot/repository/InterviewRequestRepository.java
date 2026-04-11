package com.interviewpartner.bot.repository;

import com.interviewpartner.bot.model.InterviewFormat;
import com.interviewpartner.bot.model.InterviewRequest;
import com.interviewpartner.bot.model.InterviewRequestStatus;
import com.interviewpartner.bot.model.Language;
import com.interviewpartner.bot.model.Level;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface InterviewRequestRepository extends JpaRepository<InterviewRequest, Long> {

    @Query("""
            select r from InterviewRequest r
            left join fetch r.candidate
            join fetch r.slotOwner
            where r.id = :id and r.status = :status
            """)
    Optional<InterviewRequest> findByIdAndStatus(@Param("id") Long id, @Param("status") InterviewRequestStatus status);

    @Query("""
            select r from InterviewRequest r
            left join fetch r.candidate
            join fetch r.slotOwner
            where (r.slotOwner.id = :userId or (r.candidate is not null and r.candidate.id = :userId))
              and (:status is null or r.status = :status)
            order by r.dateTime desc
            """)
    List<InterviewRequest> findByUserIdAndOptionalStatus(@Param("userId") Long userId,
                                                         @Param("status") InterviewRequestStatus status);

    @Query("""
            select r from InterviewRequest r
            join fetch r.slotOwner
            where r.candidate is null
              and r.language = :language
              and r.status = com.interviewpartner.bot.model.InterviewRequestStatus.PENDING
              and r.dateTime > :now
              and r.slotOwner.id <> :excludeUserId
            order by r.dateTime asc
            """)
    List<InterviewRequest> findOpenSoloRequests(@Param("language") Language language,
                                                @Param("excludeUserId") Long excludeUserId,
                                                @Param("now") LocalDateTime now);

    boolean existsBySlotOwnerIdAndCandidateIsNullAndLanguageAndFormatAndDateTimeAndDurationMinutesAndStatusAndLevel(
            Long slotOwnerId,
            Language language,
            InterviewFormat format,
            LocalDateTime dateTime,
            Integer durationMinutes,
            InterviewRequestStatus status,
            Level level
    );

    boolean existsBySlotOwnerIdAndCandidateIdAndLanguageAndFormatAndDateTimeAndDurationMinutesAndStatus(
            Long slotOwnerId,
            Long candidateId,
            Language language,
            InterviewFormat format,
            LocalDateTime dateTime,
            Integer durationMinutes,
            InterviewRequestStatus status
    );
}
