package com.interviewpartner.bot.service;

import com.interviewpartner.bot.exception.ScheduleOverlapException;
import com.interviewpartner.bot.model.Language;
import com.interviewpartner.bot.model.Level;
import com.interviewpartner.bot.model.User;
import com.interviewpartner.bot.repository.ScheduleRepository;
import com.interviewpartner.bot.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
@Import(ScheduleServiceImpl.class)
class ScheduleServiceTest {

    @Autowired
    private ScheduleService scheduleService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ScheduleRepository scheduleRepository;

    @Test
    void addAvailability_shouldCreateSlot() {
        var user = createUser(1L);

        var slot = scheduleService.addAvailability(
                user.getId(),
                Language.JAVA,
                DayOfWeek.MONDAY,
                LocalTime.of(10, 0),
                LocalTime.of(12, 0)
        );

        assertThat(slot.getId()).isNotNull();
        assertThat(scheduleRepository.findByUserId(user.getId())).hasSize(1);
    }

    @Test
    void addAvailability_shouldRejectOverlappingSlots() {
        var user = createUser(2L);

        scheduleService.addAvailability(
                user.getId(),
                Language.JAVA,
                DayOfWeek.MONDAY,
                LocalTime.of(10, 0),
                LocalTime.of(12, 0)
        );

        assertThatThrownBy(() -> scheduleService.addAvailability(
                user.getId(),
                Language.JAVA,
                DayOfWeek.MONDAY,
                LocalTime.of(11, 0),
                LocalTime.of(13, 0)
        )).isInstanceOf(ScheduleOverlapException.class);
    }

    @Test
    void isUserAvailable_shouldReturnTrueIfWithinSlot() {
        var user = createUser(3L);

        scheduleService.addAvailability(
                user.getId(),
                Language.JAVA,
                DayOfWeek.TUESDAY,
                LocalTime.of(10, 0),
                LocalTime.of(12, 0)
        );

        var available = scheduleService.isUserAvailable(user.getId(), LocalDateTime.of(2026, 3, 17, 11, 0)); // Tuesday
        assertThat(available).isTrue();
    }

    @Test
    void removeAvailability_shouldDeleteSlot() {
        var user = createUser(4L);

        var slot = scheduleService.addAvailability(
                user.getId(),
                Language.JAVA,
                DayOfWeek.FRIDAY,
                LocalTime.of(10, 0),
                LocalTime.of(12, 0)
        );

        scheduleService.removeAvailability(slot.getId());
        assertThat(scheduleRepository.findByUserId(user.getId())).isEmpty();
    }

    private User createUser(long telegramId) {
        return userRepository.saveAndFlush(User.builder()
                .telegramId(telegramId)
                .username("u" + telegramId)
                .language(Language.RUSSIAN)
                .level(Level.JUNIOR)
                .build());
    }
}

