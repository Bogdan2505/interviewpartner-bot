package com.interviewpartner.bot.service.notification;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ReminderSchedulerTest {

    @Test
    void shouldCallNotificationServiceOnTick() {
        NotificationDispatchService notificationService = mock(NotificationDispatchService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-03-18T09:00:00Z"), ZoneOffset.UTC);
        ReminderScheduler scheduler = new ReminderScheduler(notificationService, clock);

        scheduler.checkUpcomingInterviews();

        verify(notificationService).tick(any(LocalDateTime.class));
    }
}

