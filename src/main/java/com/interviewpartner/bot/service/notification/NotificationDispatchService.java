package com.interviewpartner.bot.service.notification;

import com.interviewpartner.bot.model.Interview;
import com.interviewpartner.bot.model.InterviewStatus;
import com.interviewpartner.bot.model.Notification;
import com.interviewpartner.bot.model.ReminderType;
import com.interviewpartner.bot.repository.InterviewRepository;
import com.interviewpartner.bot.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationDispatchService {
    private static final List<ReminderType> ACTIVE_REMINDER_TYPES = List.of(
            ReminderType.HOURS_24,
            ReminderType.MINUTES_15
    );
    private static final int DUE_PAGE_SIZE = 200;

    private final InterviewRepository interviewRepository;
    private final NotificationRepository notificationRepository;
    private final Optional<ReminderSender> reminderSender; // может отсутствовать без токена

    @Transactional
    public boolean tick(LocalDateTime now) {
        int created = ensureNotificationsForUpcomingInterviews(now);
        int dispatched = dispatchDue(now);
        return created > 0 || dispatched > 0;
    }

    @Transactional
    public int ensureNotificationsForUpcomingInterviews(LocalDateTime now) {
        // Достаточно смотреть на горизонте 25 часов, чтобы не пропускать 24ч окно при минутных тиках
        var interviews = interviewRepository.findByStatusAndDateTimeBetween(
                InterviewStatus.SCHEDULED,
                now,
                now.plusHours(25)
        );
        if (interviews.isEmpty()) {
            return 0;
        }

        Set<Long> interviewIds = interviews.stream()
                .map(Interview::getId)
                .collect(java.util.stream.Collectors.toSet());
        Set<NotificationKey> existingKeys = new HashSet<>();
        notificationRepository.findExistingByInterviewIdInAndTypeIn(interviewIds, ACTIVE_REMINDER_TYPES)
                .forEach(n -> existingKeys.add(new NotificationKey(n.getInterview().getId(), n.getType())));

        List<Notification> toCreate = new ArrayList<>();

        for (var interview : interviews) {
            collectIfMissing(existingKeys, toCreate, interview.getId(), ReminderType.HOURS_24, interview.getDateTime().minusHours(24), interview, now);
            collectIfMissing(existingKeys, toCreate, interview.getId(), ReminderType.MINUTES_15, interview.getDateTime().minusMinutes(15), interview, now);
        }
        if (!toCreate.isEmpty()) {
            notificationRepository.saveAll(toCreate);
        }
        return toCreate.size();
    }

    @Transactional
    public int dispatchDue(LocalDateTime now) {
        int processed = 0;
        List<Notification> due;
        do {
            due = notificationRepository.findBySentAtIsNullAndScheduledAtLessThanEqual(
                    now,
                    PageRequest.of(0, DUE_PAGE_SIZE)
            ).getContent();
            processed += due.size();
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
        } while (!due.isEmpty());
        return processed;
    }

    /**
     * Если собеседование создали поздно, момент «за 24 ч» уже в прошлом — не шлём текст «24 часа», когда до старта остались часы.
     */
    static boolean shouldSkipStaleReminder(Interview interview, ReminderType type, LocalDateTime now) {
        LocalDateTime start = interview.getDateTime();
        if (!start.isAfter(now)) {
            return true;
        }
        Duration until = Duration.between(now, start);
        return switch (type) {
            case HOURS_24 -> until.toHours() < 20;
            case MINUTES_15 -> until.toMinutes() > 25 || until.toMinutes() < 2;
            case HOURS_1, START -> true;
        };
    }

    private void collectIfMissing(
            Set<NotificationKey> existingKeys,
            List<Notification> toCreate,
            Long interviewId,
            ReminderType type,
            LocalDateTime scheduledAt,
            Interview interview,
            LocalDateTime now
    ) {
        if (!scheduledAt.isAfter(now)) {
            return;
        }
        NotificationKey key = new NotificationKey(interviewId, type);
        if (!existingKeys.add(key)) {
            return;
        }
        toCreate.add(Notification.builder()
                .interview(interview)
                .type(type)
                .scheduledAt(scheduledAt)
                .sentAt(null)
                .build());
    }

    private record NotificationKey(Long interviewId, ReminderType type) { }
}

