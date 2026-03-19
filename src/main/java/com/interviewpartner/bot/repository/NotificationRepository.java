package com.interviewpartner.bot.repository;

import com.interviewpartner.bot.model.Notification;
import com.interviewpartner.bot.model.ReminderType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    boolean existsByInterviewIdAndType(Long interviewId, ReminderType type);

    List<Notification> findBySentAtIsNullAndScheduledAtLessThanEqual(LocalDateTime now);
}

