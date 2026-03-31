package com.interviewpartner.bot.service.notification;

import com.interviewpartner.bot.model.Interview;
import com.interviewpartner.bot.model.InterviewStatus;
import com.interviewpartner.bot.model.Notification;
import com.interviewpartner.bot.model.ReminderType;
import com.interviewpartner.bot.repository.InterviewRepository;
import com.interviewpartner.bot.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
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
            createIfMissing(interview.getId(), ReminderType.HOURS_24, interview.getDateTime().minusHours(24), interview, now);
            createIfMissing(interview.getId(), ReminderType.HOURS_1, interview.getDateTime().minusHours(1), interview, now);
            createIfMissing(interview.getId(), ReminderType.MINUTES_15, interview.getDateTime().minusMinutes(15), interview, now);
            createIfMissing(interview.getId(), ReminderType.START, interview.getDateTime(), interview, now);
        }
    }

    @Transactional
    public void dispatchDue(LocalDateTime now) {
        List<Notification> due = notificationRepository.findBySentAtIsNullAndScheduledAtLessThanEqual(now);
        for (Notification n : due) {
            try {
                Interview interview = n.getInterview();
                if (shouldSkipStaleReminder(interview, n.getType(), now)) {
                    log.info("Пропуск устаревшего напоминания (текст уже не соответствует времени): notificationId={}, type={}, interviewId={}",
                            n.getId(), n.getType(), interview.getId());
                    n.setSentAt(now);
                    notificationRepository.save(n);
                    continue;
                }
                if (reminderSender.isPresent()) {
                    reminderSender.get().sendReminder(interview, n.getType());
                } else {
                    log.info("Reminder due (no sender configured): interviewId={}, type={}", interview.getId(), n.getType());
                }
                n.setSentAt(now);
                notificationRepository.save(n);
            } catch (Exception e) {
                log.warn("Failed to send reminder: notificationId={}", n.getId(), e);
            }
        }
    }

    /**
     * Если собеседование создали поздно, момент «за 24 ч» уже в прошлом — не шлём текст «24 часа», когда до старта остались часы.
     */
    static boolean shouldSkipStaleReminder(Interview interview, ReminderType type, LocalDateTime now) {
        LocalDateTime start = interview.getDateTime();
        if (type == ReminderType.START) {
            // Шлём после наступления времени начала; не шлём «старт» с опозданием более 2 ч
            return now.isBefore(start) || now.isAfter(start.plusHours(2));
        }
        if (!start.isAfter(now)) {
            return true;
        }
        Duration until = Duration.between(now, start);
        return switch (type) {
            case HOURS_24 -> until.toHours() < 20;
            case HOURS_1 -> until.toMinutes() < 45 || until.toHours() >= 2;
            case MINUTES_15 -> until.toMinutes() > 25 || until.toMinutes() < 2;
            case START -> throw new IllegalStateException("START обрабатывается выше");
        };
    }

    private void createIfMissing(Long interviewId, ReminderType type, LocalDateTime scheduledAt, Interview interview, LocalDateTime now) {
        if (!scheduledAt.isAfter(now)) {
            return;
        }
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

