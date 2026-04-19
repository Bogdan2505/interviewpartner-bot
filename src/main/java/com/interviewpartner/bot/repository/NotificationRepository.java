package com.interviewpartner.bot.repository;

import com.interviewpartner.bot.model.Notification;
import com.interviewpartner.bot.model.ReminderType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    boolean existsByInterviewIdAndType(Long interviewId, ReminderType type);

    @Query("""
            select n from Notification n
            where n.interview.id in :interviewIds
              and n.type in :types
            """)
    List<Notification> findExistingByInterviewIdInAndTypeIn(
            @Param("interviewIds") Collection<Long> interviewIds,
            @Param("types") Collection<ReminderType> types
    );

    Page<Notification> findBySentAtIsNullAndScheduledAtLessThanEqual(LocalDateTime now, Pageable pageable);
}

