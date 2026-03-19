package com.interviewpartner.bot.service.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReminderScheduler {

    private final NotificationDispatchService dispatchService;
    private final Clock clock;

    @Scheduled(fixedRateString = "PT60S")
    public void checkUpcomingInterviews() {
        LocalDateTime now = LocalDateTime.now(clock);
        dispatchService.tick(now);
        log.debug("Reminder tick at {}", now);
    }
}

