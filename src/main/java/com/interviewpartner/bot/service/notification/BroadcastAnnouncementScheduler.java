package com.interviewpartner.bot.service.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Периодически проверяет таблицу {@code broadcast_announcements} и рассылает подходящие сообщения.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBean(BroadcastAnnouncementDispatchService.class)
public class BroadcastAnnouncementScheduler {

    private final BroadcastAnnouncementDispatchService dispatchService;
    private final Clock clock;
    @Value("${interviewpartner.notification.broadcast-rate:PT60S}")
    private Duration baseRate;
    @Value("${interviewpartner.notification.broadcast-backoff.step1-rate:PT120S}")
    private Duration step1Rate;
    @Value("${interviewpartner.notification.broadcast-backoff.step2-rate:PT300S}")
    private Duration step2Rate;
    @Value("${interviewpartner.notification.broadcast-backoff.step1-empty-streak:3}")
    private int step1EmptyStreak;
    @Value("${interviewpartner.notification.broadcast-backoff.step2-empty-streak:8}")
    private int step2EmptyStreak;

    private int emptyStreak = 0;
    private LocalDateTime nextAllowedRunAt = LocalDateTime.MIN;

    @Scheduled(fixedRateString = "${interviewpartner.notification.broadcast-rate:PT60S}")
    public synchronized void tick() {
        LocalDateTime now = LocalDateTime.now(clock);
        if (now.isBefore(nextAllowedRunAt)) {
            log.debug("Broadcast tick skipped by backoff: now={}, nextAllowedRunAt={}, emptyStreak={}",
                    now, nextAllowedRunAt, emptyStreak);
            return;
        }
        try {
            boolean hadWork = dispatchService.dispatchDue();
            if (hadWork) {
                emptyStreak = 0;
                nextAllowedRunAt = now.plus(baseRate);
                return;
            }
            emptyStreak++;
            nextAllowedRunAt = now.plus(resolveDelayForEmptyStreak(emptyStreak));
        } catch (Exception e) {
            log.error("Ошибка при рассылке объявлений", e);
        }
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
