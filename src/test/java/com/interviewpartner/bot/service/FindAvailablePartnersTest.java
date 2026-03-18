package com.interviewpartner.bot.service;

import com.interviewpartner.bot.model.InterviewFormat;
import com.interviewpartner.bot.model.InterviewStatus;
import com.interviewpartner.bot.model.Language;
import com.interviewpartner.bot.model.Level;
import com.interviewpartner.bot.model.User;
import com.interviewpartner.bot.repository.InterviewRepository;
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

@DataJpaTest
@ActiveProfiles("test")
@Import({InterviewServiceImpl.class, ScheduleServiceImpl.class})
class FindAvailablePartnersTest {

    @Autowired
    private InterviewService interviewService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ScheduleService scheduleService;

    @Autowired
    private InterviewRepository interviewRepository;

    @Test
    void shouldReturnOnlyUsersWithSameLanguageAndAvailabilityAndNoConflicts() {
        var requester = userRepository.saveAndFlush(User.builder()
                .telegramId(1L).username("req").language(Language.RUSSIAN).level(Level.JUNIOR)
                .build());
        var ok = userRepository.saveAndFlush(User.builder()
                .telegramId(2L).username("ok").language(Language.RUSSIAN).level(Level.JUNIOR)
                .build());
        var wrongLang = userRepository.saveAndFlush(User.builder()
                .telegramId(3L).username("en").language(Language.ENGLISH).level(Level.JUNIOR)
                .build());
        var busy = userRepository.saveAndFlush(User.builder()
                .telegramId(4L).username("busy").language(Language.RUSSIAN).level(Level.JUNIOR)
                .build());
        var other = userRepository.saveAndFlush(User.builder()
                .telegramId(5L).username("other").language(Language.RUSSIAN).level(Level.JUNIOR)
                .build());

        LocalDateTime when = LocalDateTime.of(2026, 3, 23, 10, 0); // MONDAY
        scheduleService.addAvailability(ok.getId(), DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(12, 0));
        scheduleService.addAvailability(busy.getId(), DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(12, 0));
        scheduleService.addAvailability(wrongLang.getId(), DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(12, 0));

        interviewRepository.saveAndFlush(com.interviewpartner.bot.model.Interview.builder()
                .candidate(busy)
                .interviewer(other)
                .language(Language.RUSSIAN)
                .format(InterviewFormat.TECHNICAL)
                .dateTime(when)
                .duration(60)
                .status(InterviewStatus.SCHEDULED)
                .build());

        var found = interviewService.findAvailablePartners(requester.getId(), Language.RUSSIAN, when);

        assertThat(found).extracting(User::getId).contains(ok.getId());
        assertThat(found).extracting(User::getId).doesNotContain(busy.getId());
        assertThat(found).extracting(User::getId).doesNotContain(wrongLang.getId());
        assertThat(found).extracting(User::getId).doesNotContain(requester.getId());
    }
}

