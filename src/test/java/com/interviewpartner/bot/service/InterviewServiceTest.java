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
import com.interviewpartner.bot.service.CandidateSlotServiceImpl;
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
@Import({InterviewServiceImpl.class, UserServiceImpl.class, CandidateSlotServiceImpl.class})
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
                1L, 2L, Language.RUSSIAN, null, InterviewFormat.TECHNICAL,
                futureAt(10, 12, 0), 60, true
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
                null,
                InterviewFormat.TECHNICAL,
                futureAt(5, 12, 0),
                60,
                true
        );

        assertThat(created.getId()).isNotNull();
        assertThat(created.getStatus()).isEqualTo(InterviewStatus.SCHEDULED);
        assertThat(interviewRepository.findAll()).hasSize(1);
    }

    @Test
    void createInterview_shouldThrowOnTimeConflict() {
        var candidate = createUser(10L, "c");
        var interviewer = createUser(20L, "i");

        LocalDateTime firstStart = futureAt(7, 12, 0);
        interviewService.createInterview(
                candidate.getId(),
                interviewer.getId(),
                Language.RUSSIAN,
                null,
                InterviewFormat.TECHNICAL,
                firstStart,
                60,
                true
        );

        assertThatThrownBy(() -> interviewService.createInterview(
                candidate.getId(),
                interviewer.getId(),
                Language.RUSSIAN,
                null,
                InterviewFormat.BEHAVIORAL,
                firstStart.plusMinutes(30),
                30,
                true
        )).isInstanceOf(InterviewConflictException.class);
    }

    @Test
    void createInterview_shouldRejectPastTime() {
        var candidate = createUser(991L, "c991");
        var interviewer = createUser(992L, "i992");

        assertThatThrownBy(() -> interviewService.createInterview(
                candidate.getId(),
                interviewer.getId(),
                Language.RUSSIAN,
                null,
                InterviewFormat.TECHNICAL,
                LocalDateTime.now().minusDays(1),
                60,
                true
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cancelInterview_shouldUpdateStatus() {
        var candidate = createUser(100L, "c");
        var interviewer = createUser(200L, "i");

        var interview = interviewService.createInterview(
                candidate.getId(),
                interviewer.getId(),
                Language.ENGLISH,
                null,
                InterviewFormat.BEHAVIORAL,
                futureAt(8, 14, 0),
                30,
                true
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
                null,
                InterviewFormat.TECHNICAL,
                futureAt(9, 16, 0),
                30,
                true
        );

        var completed = interviewService.completeInterview(interview.getId());
        assertThat(completed.getStatus()).isEqualTo(InterviewStatus.COMPLETED);
    }

    @Test
    void getInterviewWithParticipants_returnsInterviewWithUsers() {
        var candidate = createUser(310L, "c310");
        var interviewer = createUser(311L, "i311");
        var created = interviewService.createInterview(
                candidate.getId(),
                interviewer.getId(),
                Language.RUSSIAN,
                null,
                InterviewFormat.TECHNICAL,
                futureAt(11, 15, 0),
                60,
                true
        );

        var loaded = interviewService.getInterviewWithParticipants(created.getId());

        assertThat(loaded.getId()).isEqualTo(created.getId());
        assertThat(loaded.getCandidate().getUsername()).isEqualTo("c310");
        assertThat(loaded.getInterviewer().getUsername()).isEqualTo("i311");
    }

    private User createUser(long telegramId, String username) {
        return userRepository.saveAndFlush(User.builder()
                .telegramId(telegramId)
                .username(username)
                .language(Language.RUSSIAN)
                .level(Level.JUNIOR)
                .build());
    }

    private static LocalDateTime futureAt(int daysFromNow, int hour, int minute) {
        return LocalDateTime.now().plusDays(daysFromNow).withHour(hour).withMinute(minute).withSecond(0).withNano(0);
    }
}

