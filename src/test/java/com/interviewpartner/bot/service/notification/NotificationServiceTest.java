package com.interviewpartner.bot.service.notification;

import com.interviewpartner.bot.model.Interview;
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

@DataJpaTest
@ActiveProfiles("test")
@Import(NotificationServiceImpl.class)
class NotificationServiceTest {

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private InterviewRepository interviewRepository;

    @Test
    void shouldReturnOnlyScheduledWithinNextHour() {
        var u1 = userRepository.saveAndFlush(User.builder()
                .telegramId(1L).username("u1").language(Language.RUSSIAN).level(Level.JUNIOR)
                .build());
        var u2 = userRepository.saveAndFlush(User.builder()
                .telegramId(2L).username("u2").language(Language.RUSSIAN).level(Level.JUNIOR)
                .build());

        LocalDateTime now = LocalDateTime.of(2026, 3, 18, 12, 0);
        Interview inWindow = interviewRepository.saveAndFlush(Interview.builder()
                .candidate(u1).interviewer(u2)
                .language(Language.RUSSIAN).format(InterviewFormat.TECHNICAL)
                .dateTime(now.plusMinutes(30)).duration(60).status(InterviewStatus.SCHEDULED)
                .build());
        interviewRepository.saveAndFlush(Interview.builder()
                .candidate(u1).interviewer(u2)
                .language(Language.RUSSIAN).format(InterviewFormat.TECHNICAL)
                .dateTime(now.plusHours(2)).duration(60).status(InterviewStatus.SCHEDULED)
                .build());
        interviewRepository.saveAndFlush(Interview.builder()
                .candidate(u1).interviewer(u2)
                .language(Language.RUSSIAN).format(InterviewFormat.TECHNICAL)
                .dateTime(now.plusMinutes(20)).duration(60).status(InterviewStatus.CANCELLED)
                .build());

        var found = notificationService.findUpcomingInterviewsToCheck(now);
        assertThat(found).extracting(Interview::getId).contains(inWindow.getId());
        assertThat(found).hasSize(1);
    }
}

