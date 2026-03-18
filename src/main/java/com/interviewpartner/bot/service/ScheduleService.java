package com.interviewpartner.bot.service;

import com.interviewpartner.bot.model.Schedule;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

public interface ScheduleService {
    Schedule addAvailability(Long userId, DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime);

    void removeAvailability(Long scheduleId);

    List<Schedule> getUserSchedule(Long userId);

    boolean isUserAvailable(Long userId, LocalDateTime dateTime);
}

