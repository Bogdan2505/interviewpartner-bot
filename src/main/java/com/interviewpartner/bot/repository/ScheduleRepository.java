package com.interviewpartner.bot.repository;

import com.interviewpartner.bot.model.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.DayOfWeek;
import java.util.List;

public interface ScheduleRepository extends JpaRepository<Schedule, Long> {
    List<Schedule> findByUserId(Long userId);

    List<Schedule> findByUserIdAndDayOfWeek(Long userId, DayOfWeek dayOfWeek);
}

