package com.interviewpartner.bot.service.notification;

import com.interviewpartner.bot.model.Interview;
import com.interviewpartner.bot.model.InterviewFormat;
import com.interviewpartner.bot.model.InterviewStatus;
import com.interviewpartner.bot.model.Language;
import com.interviewpartner.bot.model.Notification;
import com.interviewpartner.bot.model.ReminderType;
import com.interviewpartner.bot.model.User;
import com.interviewpartner.bot.repository.InterviewRepository;
import com.interviewpartner.bot.repository.NotificationRepository;
import com.interviewpartner.bot.repository.UserRepository;
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
@Import({})
class NotificationDispatchDueTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private InterviewRepository interviewRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Test
    void dispatchDue_shouldMarkSent() {
        ReminderSender sender = (interview, type) -> { };
        NotificationDispatchService service = new NotificationDispatchService(
                interviewRepository,
                notificationRepository,
                Optional.of(sender)
        );

        User u1 = userRepository.saveAndFlush(User.builder()
                .telegramId(1L).username("u1")
                .build());
        User u2 = userRepository.saveAndFlush(User.builder()
                .telegramId(2L).username("u2")
                .build());
        LocalDateTime now = LocalDateTime.of(2026, 3, 18, 12, 0);
        Interview interview = interviewRepository.saveAndFlush(Interview.builder()
                .candidate(u1).interviewer(u2)
                .language(Language.RUSSIAN).format(InterviewFormat.TECHNICAL)
                .dateTime(now.plusMinutes(15)).duration(60).status(InterviewStatus.SCHEDULED)
                .build());
        notificationRepository.saveAndFlush(Notification.builder()
                .interview(interview)
                .type(ReminderType.MINUTES_15)
                .scheduledAt(now.minusMinutes(1))
                .sentAt(null)
                .build());

        service.dispatchDue(now);

        var n = notificationRepository.findAll().get(0);
        assertThat(n.getSentAt()).isEqualTo(now);
    }
}

