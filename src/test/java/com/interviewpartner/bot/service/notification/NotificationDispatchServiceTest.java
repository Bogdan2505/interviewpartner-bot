package com.interviewpartner.bot.service.notification;

import com.interviewpartner.bot.model.Interview;
import com.interviewpartner.bot.model.InterviewFormat;
import com.interviewpartner.bot.model.InterviewStatus;
import com.interviewpartner.bot.model.Language;
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
    void ensureNotifications_shouldCreateOnlyActiveReminderTypesOnce() {
        // sender отсутствует в DataJpaTest, поэтому Optional.empty()
        NotificationDispatchService service = new NotificationDispatchService(
                interviewRepository,
                notificationRepository,
                Optional.empty()
        );

        User u1 = userRepository.saveAndFlush(user("u1", 1L));
        User u2 = userRepository.saveAndFlush(user("u2", 2L));
        LocalDateTime now = LocalDateTime.of(2026, 3, 18, 12, 0);
        // В окне [now, now+25ч) для ensureNotifications и позже now+24ч, чтобы «за 24ч» ещё не наступило
        Interview interview = interviewRepository.saveAndFlush(Interview.builder()
                .candidate(u1)
                .interviewer(u2)
                .language(Language.RUSSIAN)
                .format(InterviewFormat.TECHNICAL)
                .dateTime(now.plusHours(24).plusMinutes(30))
                .duration(60)
                .status(InterviewStatus.SCHEDULED)
                .build());

        service.ensureNotificationsForUpcomingInterviews(now);
        service.ensureNotificationsForUpcomingInterviews(now);

        var all = notificationRepository.findAll();
        assertThat(all).hasSize(2);
        assertThat(all).extracting(n -> n.getType()).containsExactlyInAnyOrder(
                ReminderType.HOURS_24, ReminderType.MINUTES_15
        );
        assertThat(all).allMatch(n -> n.getInterview().getId().equals(interview.getId()));
    }

    private static User user(String username, long telegramId) {
        return User.builder()
                .telegramId(telegramId)
                .username(username)
                .build();
    }
}

