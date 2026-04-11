package com.interviewpartner.bot.service;

import com.interviewpartner.bot.config.ClockConfig;
import com.interviewpartner.bot.model.InterviewFormat;
import com.interviewpartner.bot.model.InterviewRequestStatus;
import com.interviewpartner.bot.model.InterviewStatus;
import com.interviewpartner.bot.model.Language;
import com.interviewpartner.bot.model.Level;
import com.interviewpartner.bot.model.User;
import com.interviewpartner.bot.repository.InterviewRepository;
import com.interviewpartner.bot.repository.InterviewRequestRepository;
import com.interviewpartner.bot.repository.UserRepository;
import com.interviewpartner.bot.service.request.InterviewRequestService;
import com.interviewpartner.bot.service.request.InterviewRequestServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Два открытых слота → два join: без второй строки заявки на слот (нет «висящего» solo PENDING).
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({ClockConfig.class, InterviewRequestServiceImpl.class, InterviewServiceImpl.class, UserServiceImpl.class})
class OpenSlotBookingIntegrationTest {

    @Autowired
    private InterviewRequestService interviewRequestService;

    @Autowired
    private InterviewService interviewService;

    @Autowired
    private InterviewRequestRepository interviewRequestRepository;

    @Autowired
    private InterviewRepository interviewRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void twoOpenSlots_twoJoins_leaveTwoAcceptedRequests_twoScheduledInterviews_noOrphanPending() {
        User joiner = createUser(10_001L, "joiner");
        User owner1 = createUser(10_002L, "owner1");
        User owner2 = createUser(10_003L, "owner2");

        LocalDateTime t1 = futureAt(6, 11, 0);
        LocalDateTime t2 = futureAt(6, 15, 0);
        LocalDateTime now = LocalDateTime.now();

        var solo1 = interviewRequestService.createRequest(
                owner1.getId(), owner1.getId(), Language.RUSSIAN, InterviewFormat.TECHNICAL, t1, 60);
        var solo2 = interviewRequestService.createRequest(
                owner2.getId(), owner2.getId(), Language.RUSSIAN, InterviewFormat.TECHNICAL, t2, 60);

        interviewRequestService.completeOpenSlotWithJoiner(
                solo1.getId(), joiner.getId(), Language.RUSSIAN, InterviewFormat.TECHNICAL, t1, 60, now);
        interviewRequestService.completeOpenSlotWithJoiner(
                solo2.getId(), joiner.getId(), Language.RUSSIAN, InterviewFormat.TECHNICAL, t2, 60, now);

        assertThat(interviewRequestRepository.findAll()).hasSize(2);
        assertThat(interviewRequestRepository.findAll())
                .allMatch(r -> r.getStatus() == InterviewRequestStatus.ACCEPTED && r.getPartner() != null);

        long orphanPending = interviewRequestRepository.findAll().stream()
                .filter(r -> r.getStatus() == InterviewRequestStatus.PENDING && r.getPartner() == null)
                .count();
        assertThat(orphanPending).isZero();

        interviewService.createInterview(
                joiner.getId(), owner1.getId(), Language.RUSSIAN, null, InterviewFormat.TECHNICAL, t1, 60, true);
        interviewService.createInterview(
                joiner.getId(), owner2.getId(), Language.RUSSIAN, null, InterviewFormat.TECHNICAL, t2, 60, true);

        assertThat(interviewRepository.findAll()).hasSize(2);
        assertThat(interviewRepository.findAll()).allMatch(i -> i.getStatus() == InterviewStatus.SCHEDULED);
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
