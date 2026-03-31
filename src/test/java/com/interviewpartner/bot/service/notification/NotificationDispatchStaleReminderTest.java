package com.interviewpartner.bot.service.notification;

import com.interviewpartner.bot.model.Interview;
import com.interviewpartner.bot.model.ReminderType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationDispatchStaleReminderTest {

    private final LocalDateTime now = LocalDateTime.of(2026, 3, 24, 12, 0);

    @Test
    void hours24_skippedWhenLessThan20hBefore() {
        Interview interview = mock(Interview.class);
        when(interview.getDateTime()).thenReturn(now.plusHours(2));
        assertThat(NotificationDispatchService.shouldSkipStaleReminder(interview, ReminderType.HOURS_24, now)).isTrue();
    }

    @Test
    void hours24_notSkippedWhenAbout24hBefore() {
        Interview interview = mock(Interview.class);
        when(interview.getDateTime()).thenReturn(now.plusHours(23));
        assertThat(NotificationDispatchService.shouldSkipStaleReminder(interview, ReminderType.HOURS_24, now)).isFalse();
    }

    @Test
    void minutes15_skippedWhenMoreThan25mBefore() {
        Interview interview = mock(Interview.class);
        when(interview.getDateTime()).thenReturn(now.plusMinutes(30));
        assertThat(NotificationDispatchService.shouldSkipStaleReminder(interview, ReminderType.MINUTES_15, now)).isTrue();
    }

    @Test
    void start_skippedWhenBeforeScheduledTime() {
        Interview interview = mock(Interview.class);
        when(interview.getDateTime()).thenReturn(now.plusMinutes(5));
        assertThat(NotificationDispatchService.shouldSkipStaleReminder(interview, ReminderType.START, now)).isTrue();
    }

    @Test
    void start_notSkippedWhenJustAfterStart() {
        Interview interview = mock(Interview.class);
        when(interview.getDateTime()).thenReturn(now);
        assertThat(NotificationDispatchService.shouldSkipStaleReminder(interview, ReminderType.START, now)).isFalse();
    }

    @Test
    void start_skippedWhenMoreThanTwoHoursAfterStart() {
        Interview interview = mock(Interview.class);
        when(interview.getDateTime()).thenReturn(now.minusHours(3));
        assertThat(NotificationDispatchService.shouldSkipStaleReminder(interview, ReminderType.START, now)).isTrue();
    }
}
