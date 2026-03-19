package com.interviewpartner.bot.service.notification;

import com.interviewpartner.bot.model.InterviewStatus;
import com.interviewpartner.bot.model.Notification;
import com.interviewpartner.bot.model.ReminderType;
import com.interviewpartner.bot.repository.InterviewRepository;
import com.interviewpartner.bot.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationDispatchService {

    private final InterviewRepository interviewRepository;
    private final NotificationRepository notificationRepository;
    private final Optional<ReminderSender> reminderSender; // может отсутствовать без токена

    @Transactional
    public void tick(LocalDateTime now) {
        ensureNotificationsForUpcomingInterviews(now);
        dispatchDue(now);
    }

    @Transactional
    public void ensureNotificationsForUpcomingInterviews(LocalDateTime now) {
        // Достаточно смотреть на горизонте 25 часов, чтобы не пропускать 24ч окно при минутных тиках
        var interviews = interviewRepository.findByStatusAndDateTimeBetween(
                InterviewStatus.SCHEDULED,
                now,
                now.plusHours(25)
        );

        for (var interview : interviews) {
            createIfMissing(interview.getId(), ReminderType.HOURS_24, interview.getDateTime().minusHours(24), interview);
            createIfMissing(interview.getId(), ReminderType.HOURS_1, interview.getDateTime().minusHours(1), interview);
            createIfMissing(interview.getId(), ReminderType.MINUTES_15, interview.getDateTime().minusMinutes(15), interview);
        }
    }

    @Transactional
    public void dispatchDue(LocalDateTime now) {
        List<Notification> due = notificationRepository.findBySentAtIsNullAndScheduledAtLessThanEqual(now);
        for (Notification n : due) {
            try {
                if (reminderSender.isPresent()) {
                    reminderSender.get().sendReminder(n.getInterview(), n.getType());
                } else {
                    log.info("Reminder due (no sender configured): interviewId={}, type={}", n.getInterview().getId(), n.getType());
                }
                n.setSentAt(now);
                notificationRepository.save(n);
            } catch (Exception e) {
                log.warn("Failed to send reminder: notificationId={}", n.getId(), e);
            }
        }
    }

    private void createIfMissing(Long interviewId, ReminderType type, LocalDateTime scheduledAt, com.interviewpartner.bot.model.Interview interview) {
        if (notificationRepository.existsByInterviewIdAndType(interviewId, type)) {
            return;
        }
        notificationRepository.save(Notification.builder()
                .interview(interview)
                .type(type)
                .scheduledAt(scheduledAt)
                .sentAt(null)
                .build());
    }
}

