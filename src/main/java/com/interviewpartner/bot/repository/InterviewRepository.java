package com.interviewpartner.bot.repository;

import com.interviewpartner.bot.model.Interview;
import com.interviewpartner.bot.model.InterviewStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

public interface InterviewRepository extends JpaRepository<Interview, Long> {

    List<Interview> findByDateTimeBetween(LocalDateTime startInclusive, LocalDateTime endExclusive);

    List<Interview> findByStatusAndDateTimeBetween(InterviewStatus status, LocalDateTime startInclusive, LocalDateTime endExclusive);

    @Query("""
            select i from Interview i
            where (i.candidate.id = :userId or i.interviewer.id = :userId)
              and (:status is null or i.status = :status)
            order by i.dateTime desc
            """)
    List<Interview> findByUserIdAndOptionalStatus(@Param("userId") Long userId, @Param("status") InterviewStatus status);

    @Query("""
            select i from Interview i
            where (i.candidate.id = :userId or i.interviewer.id = :userId)
              and i.dateTime < :endTime
            """)
    List<Interview> findUserInterviewsStartingBefore(
            @Param("userId") Long userId,
            @Param("endTime") LocalDateTime endTime
    );

    default List<Interview> findConflictingInterviews(Long userId, LocalDateTime requestedStart, int requestedDurationMinutes) {
        var requestedEnd = requestedStart.plusMinutes(requestedDurationMinutes);
        return findUserInterviewsStartingBefore(userId, requestedEnd).stream()
                .filter(i -> {
                    var interviewEnd = i.getDateTime().plusMinutes(i.getDuration());
                    return interviewEnd.isAfter(requestedStart);
                })
                .collect(Collectors.toList());
    }
}

