package com.interviewpartner.bot.service;

import com.interviewpartner.bot.model.Language;
import com.interviewpartner.bot.model.Schedule;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

public interface ScheduleService {
    Schedule addAvailability(Long userId, Language language, DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime);

    void removeAvailability(Long scheduleId);

    List<Schedule> getUserSchedule(Long userId);

    boolean isUserAvailable(Long userId, LocalDateTime dateTime);

    /**
     * Возвращает список начал слотов (по расписанию пользователя) в заданном диапазоне дат.
     * Не проверяет конфликты с уже назначенными собеседованиями.
     */
    List<LocalDateTime> getFreeSlotStarts(Long userId, LocalDate from, LocalDate to, int durationMinutes);
}

