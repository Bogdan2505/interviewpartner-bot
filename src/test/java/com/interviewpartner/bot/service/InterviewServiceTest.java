package com.interviewpartner.bot.service;

import com.interviewpartner.bot.exception.InterviewConflictException;
import com.interviewpartner.bot.exception.UserNotFoundException;
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

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
@Import({InterviewServiceImpl.class, UserServiceImpl.class})
class InterviewServiceTest {

    @Autowired
    private InterviewService interviewService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private InterviewRepository interviewRepository;

    @Test
    void createInterview_shouldThrowIfUserNotFound() {
        assertThatThrownBy(() -> interviewService.createInterview(
                1L, 2L, Language.RUSSIAN, InterviewFormat.TECHNICAL,
                LocalDateTime.of(2026, 3, 18, 12, 0), 60
        )).isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void createInterview_shouldCreateScheduledInterview() {
        var candidate = createUser(1L, "c");
        var interviewer = createUser(2L, "i");

        var created = interviewService.createInterview(
                candidate.getId(),
                interviewer.getId(),
                Language.RUSSIAN,
                InterviewFormat.TECHNICAL,
                LocalDateTime.of(2026, 3, 18, 12, 0),
                60
        );

        assertThat(created.getId()).isNotNull();
        assertThat(created.getStatus()).isEqualTo(InterviewStatus.SCHEDULED);
        assertThat(interviewRepository.findAll()).hasSize(1);
    }

    @Test
    void createInterview_shouldThrowOnTimeConflict() {
        var candidate = createUser(10L, "c");
        var interviewer = createUser(20L, "i");

        interviewService.createInterview(
                candidate.getId(),
                interviewer.getId(),
                Language.RUSSIAN,
                InterviewFormat.TECHNICAL,
                LocalDateTime.of(2026, 3, 18, 12, 0),
                60
        );

        assertThatThrownBy(() -> interviewService.createInterview(
                candidate.getId(),
                interviewer.getId(),
                Language.RUSSIAN,
                InterviewFormat.BEHAVIORAL,
                LocalDateTime.of(2026, 3, 18, 12, 30),
                30
        )).isInstanceOf(InterviewConflictException.class);
    }

    @Test
    void cancelInterview_shouldUpdateStatus() {
        var candidate = createUser(100L, "c");
        var interviewer = createUser(200L, "i");

        var interview = interviewService.createInterview(
                candidate.getId(),
                interviewer.getId(),
                Language.ENGLISH,
                InterviewFormat.BEHAVIORAL,
                LocalDateTime.of(2026, 3, 18, 14, 0),
                30
        );

        var cancelled = interviewService.cancelInterview(interview.getId());
        assertThat(cancelled.getStatus()).isEqualTo(InterviewStatus.CANCELLED);
    }

    @Test
    void completeInterview_shouldUpdateStatus() {
        var candidate = createUser(101L, "c");
        var interviewer = createUser(201L, "i");

        var interview = interviewService.createInterview(
                candidate.getId(),
                interviewer.getId(),
                Language.ENGLISH,
                InterviewFormat.TECHNICAL,
                LocalDateTime.of(2026, 3, 18, 16, 0),
                30
        );

        var completed = interviewService.completeInterview(interview.getId());
        assertThat(completed.getStatus()).isEqualTo(InterviewStatus.COMPLETED);
    }

    private User createUser(long telegramId, String username) {
        return userRepository.saveAndFlush(User.builder()
                .telegramId(telegramId)
                .username(username)
                .language(Language.RUSSIAN)
                .level(Level.JUNIOR)
                .build());
    }
}

