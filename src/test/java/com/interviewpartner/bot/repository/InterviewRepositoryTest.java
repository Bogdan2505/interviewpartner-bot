package com.interviewpartner.bot.repository;

import com.interviewpartner.bot.model.Interview;
import com.interviewpartner.bot.model.InterviewFormat;
import com.interviewpartner.bot.model.InterviewStatus;
import com.interviewpartner.bot.model.Language;
import com.interviewpartner.bot.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class InterviewRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private InterviewRepository interviewRepository;

    @Test
    void shouldFindConflictingInterviewsForCandidateOrInterviewer() {
        var candidate = userRepository.saveAndFlush(User.builder()
                .telegramId(1L).username("c")
                .build());
        var interviewer = userRepository.saveAndFlush(User.builder()
                .telegramId(2L).username("i")
                .build());

        var start = LocalDateTime.of(2026, 3, 18, 12, 0);
        var existing = interviewRepository.saveAndFlush(Interview.builder()
                .candidate(candidate)
                .interviewer(interviewer)
                .language(Language.RUSSIAN)
                .format(InterviewFormat.TECHNICAL)
                .dateTime(start)
                .duration(60)
                .status(InterviewStatus.SCHEDULED)
                .build());

        var conflicts = interviewRepository.findConflictingInterviews(
                candidate.getId(),
                start.plusMinutes(30),
                60
        );

        assertThat(conflicts).extracting(Interview::getId).contains(existing.getId());
    }

    @Test
    void shouldFindByDateTimeBetween() {
        var u1 = userRepository.saveAndFlush(User.builder()
                .telegramId(10L).username("a")
                .build());
        var u2 = userRepository.saveAndFlush(User.builder()
                .telegramId(11L).username("b")
                .build());

        interviewRepository.saveAllAndFlush(List.of(
                Interview.builder()
                        .candidate(u1).interviewer(u2)
                        .language(Language.ENGLISH).format(InterviewFormat.BEHAVIORAL)
                        .dateTime(LocalDateTime.of(2026, 3, 18, 10, 0))
                        .duration(30).status(InterviewStatus.SCHEDULED)
                        .build(),
                Interview.builder()
                        .candidate(u2).interviewer(u1)
                        .language(Language.ENGLISH).format(InterviewFormat.TECHNICAL)
                        .dateTime(LocalDateTime.of(2026, 3, 18, 20, 0))
                        .duration(30).status(InterviewStatus.SCHEDULED)
                        .build()
        ));

        var found = interviewRepository.findByDateTimeBetween(
                LocalDateTime.of(2026, 3, 18, 9, 0),
                LocalDateTime.of(2026, 3, 18, 12, 0)
        );

        assertThat(found).hasSize(1);
    }

    @Test
    void findByIdWithParticipants_joinFetchCandidateAndInterviewer() {
        var candidate = userRepository.saveAndFlush(User.builder()
                .telegramId(20L).username("c20")
                .build());
        var interviewer = userRepository.saveAndFlush(User.builder()
                .telegramId(21L).username("i21")
                .build());
        var saved = interviewRepository.saveAndFlush(Interview.builder()
                .candidate(candidate)
                .interviewer(interviewer)
                .language(Language.RUSSIAN)
                .format(InterviewFormat.TECHNICAL)
                .dateTime(LocalDateTime.of(2026, 4, 1, 10, 0))
                .duration(60)
                .status(InterviewStatus.SCHEDULED)
                .build());

        var found = interviewRepository.findByIdWithParticipants(saved.getId()).orElseThrow();

        assertThat(found.getCandidate().getUsername()).isEqualTo("c20");
        assertThat(found.getInterviewer().getUsername()).isEqualTo("i21");
    }
}

