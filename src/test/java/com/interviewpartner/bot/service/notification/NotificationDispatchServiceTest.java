package com.interviewpartner.bot.service.notification;

import com.interviewpartner.bot.model.Interview;
import com.interviewpartner.bot.model.InterviewFormat;
import com.interviewpartner.bot.model.InterviewStatus;
import com.interviewpartner.bot.model.Language;
import com.interviewpartner.bot.model.Level;
import com.interviewpartner.bot.model.ReminderType;
import com.interviewpartner.bot.model.User;
import com.interviewpartner.bot.repository.InterviewRepository;
import com.interviewpartner.bot.repository.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import({NotificationDispatchService.class})
class NotificationDispatchServiceTest {

    @Autowired
    private InterviewRepository interviewRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private com.interviewpartner.bot.repository.UserRepository userRepository;

    @Test
    void ensureNotifications_shouldCreateThreeTypesOnce() {
        // sender отсутствует в DataJpaTest, поэтому Optional.empty()
        NotificationDispatchService service = new NotificationDispatchService(
                interviewRepository,
                notificationRepository,
                Optional.empty()
        );

        User u1 = userRepository.saveAndFlush(user("u1", 1L));
        User u2 = userRepository.saveAndFlush(user("u2", 2L));
        LocalDateTime now = LocalDateTime.of(2026, 3, 18, 12, 0);
        Interview interview = interviewRepository.saveAndFlush(Interview.builder()
                .candidate(u1)
                .interviewer(u2)
                .language(Language.RUSSIAN)
                .format(InterviewFormat.TECHNICAL)
                .dateTime(now.plusHours(10))
                .duration(60)
                .status(InterviewStatus.SCHEDULED)
                .build());

        service.ensureNotificationsForUpcomingInterviews(now);
        service.ensureNotificationsForUpcomingInterviews(now);

        var all = notificationRepository.findAll();
        assertThat(all).hasSize(3);
        assertThat(all).extracting(n -> n.getType()).containsExactlyInAnyOrder(
                ReminderType.HOURS_24, ReminderType.HOURS_1, ReminderType.MINUTES_15
        );
        assertThat(all).allMatch(n -> n.getInterview().getId().equals(interview.getId()));
    }

    private static User user(String username, long telegramId) {
        return User.builder()
                .telegramId(telegramId)
                .username(username)
                .language(Language.RUSSIAN)
                .level(Level.JUNIOR)
                .build();
    }
}

