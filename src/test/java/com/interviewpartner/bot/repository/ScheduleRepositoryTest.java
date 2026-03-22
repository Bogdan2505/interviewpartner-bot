package com.interviewpartner.bot.repository;

import com.interviewpartner.bot.model.Language;
import com.interviewpartner.bot.model.Level;
import com.interviewpartner.bot.model.Schedule;
import com.interviewpartner.bot.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.DayOfWeek;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class ScheduleRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ScheduleRepository scheduleRepository;

    @Test
    void shouldFindByUserIdAndDayOfWeek() {
        var user = userRepository.saveAndFlush(User.builder()
                .telegramId(100L).username("u").language(Language.RUSSIAN).level(Level.JUNIOR)
                .build());

        scheduleRepository.saveAndFlush(Schedule.builder()
                .user(user)
                .language(Language.JAVA)
                .dayOfWeek(DayOfWeek.MONDAY)
                .startTime(LocalTime.of(10, 0))
                .endTime(LocalTime.of(12, 0))
                .isAvailable(true)
                .build());

        var found = scheduleRepository.findByUserIdAndDayOfWeek(user.getId(), DayOfWeek.MONDAY);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getUser().getId()).isEqualTo(user.getId());
    }
}

