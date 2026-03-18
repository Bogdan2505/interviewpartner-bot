package com.interviewpartner.bot.service;

import com.interviewpartner.bot.exception.ScheduleOverlapException;
import com.interviewpartner.bot.exception.UserNotFoundException;
import com.interviewpartner.bot.model.Schedule;
import com.interviewpartner.bot.repository.ScheduleRepository;
import com.interviewpartner.bot.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class ScheduleServiceImpl implements ScheduleService {

    private final ScheduleRepository scheduleRepository;
    private final UserRepository userRepository;

    @Override
    public Schedule addAvailability(Long userId, DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime) {
        if (!userRepository.existsById(userId)) {
            throw new UserNotFoundException("User with id=" + userId + " not found");
        }
        if (endTime.isBefore(startTime) || endTime.equals(startTime)) {
            throw new IllegalArgumentException("endTime must be after startTime");
        }

        var existing = scheduleRepository.findByUserIdAndDayOfWeek(userId, dayOfWeek);
        for (var slot : existing) {
            if (Boolean.TRUE.equals(slot.getIsAvailable()) && overlaps(startTime, endTime, slot.getStartTime(), slot.getEndTime())) {
                throw new ScheduleOverlapException("Schedule overlaps existing slot id=" + slot.getId());
            }
        }

        var user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User with id=" + userId + " not found"));

        return scheduleRepository.save(Schedule.builder()
                .user(user)
                .dayOfWeek(dayOfWeek)
                .startTime(startTime)
                .endTime(endTime)
                .isAvailable(true)
                .build());
    }

    @Override
    public void removeAvailability(Long scheduleId) {
        scheduleRepository.deleteById(scheduleId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Schedule> getUserSchedule(Long userId) {
        return scheduleRepository.findByUserId(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isUserAvailable(Long userId, LocalDateTime dateTime) {
        var day = dateTime.getDayOfWeek();
        var time = dateTime.toLocalTime();
        return scheduleRepository.findByUserIdAndDayOfWeek(userId, day).stream()
                .filter(s -> Boolean.TRUE.equals(s.getIsAvailable()))
                .anyMatch(s -> !time.isBefore(s.getStartTime()) && time.isBefore(s.getEndTime()));
    }

    private static boolean overlaps(LocalTime start1, LocalTime end1, LocalTime start2, LocalTime end2) {
        return start1.isBefore(end2) && start2.isBefore(end1);
    }
}

