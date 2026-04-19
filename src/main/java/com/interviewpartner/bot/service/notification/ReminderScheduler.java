package com.interviewpartner.bot.service.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReminderScheduler {

    private final NotificationDispatchService dispatchService;
    private final Clock clock;
    @Value("${interviewpartner.notification.reminder-rate:PT60S}")
    private Duration baseRate;
    @Value("${interviewpartner.notification.reminder-backoff.step1-rate:PT120S}")
    private Duration step1Rate;
    @Value("${interviewpartner.notification.reminder-backoff.step2-rate:PT300S}")
    private Duration step2Rate;
    @Value("${interviewpartner.notification.reminder-backoff.step1-empty-streak:3}")
    private int step1EmptyStreak;
    @Value("${interviewpartner.notification.reminder-backoff.step2-empty-streak:8}")
    private int step2EmptyStreak;

    private int emptyStreak = 0;
    private LocalDateTime nextAllowedRunAt = LocalDateTime.MIN;

    @Scheduled(fixedRateString = "${interviewpartner.notification.reminder-rate:PT60S}")
    public synchronized void checkUpcomingInterviews() {
        LocalDateTime now = LocalDateTime.now(clock);
        if (now.isBefore(nextAllowedRunAt)) {
            log.debug("Reminder tick skipped by backoff: now={}, nextAllowedRunAt={}, emptyStreak={}",
                    now, nextAllowedRunAt, emptyStreak);
            return;
        }
        boolean hadWork = dispatchService.tick(now);
        if (hadWork) {
            emptyStreak = 0;
            nextAllowedRunAt = now.plus(baseRate);
            log.debug("Reminder tick had work, backoff reset: now={}", now);
            return;
        }
        emptyStreak++;
        Duration delay = resolveDelayForEmptyStreak(emptyStreak);
        nextAllowedRunAt = now.plus(delay);
        log.debug("Reminder tick empty, backoff updated: now={}, emptyStreak={}, nextDelay={}",
                now, emptyStreak, delay);
    }

    private Duration resolveDelayForEmptyStreak(int streak) {
        if (streak >= step2EmptyStreak) {
            return step2Rate;
        }
        if (streak >= step1EmptyStreak) {
            return step1Rate;
        }
        return baseRate;
    }
}

