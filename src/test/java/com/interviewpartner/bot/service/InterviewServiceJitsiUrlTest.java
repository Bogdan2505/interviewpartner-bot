package com.interviewpartner.bot.service;

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
@Import({InterviewServiceImpl.class, UserServiceImpl.class, CandidateSlotServiceImpl.class})
class InterviewServiceJitsiUrlTest {

    @Autowired
    private InterviewService interviewService;

    @Autowired
    private InterviewRepository interviewRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void createInterview_paired_persistsJitsiUrl() {
        User candidate = saveUser(501L, "c501");
        User interviewer = saveUser(502L, "i502");

        Interview created = interviewService.createInterview(
                candidate.getId(),
                interviewer.getId(),
                Language.RUSSIAN,
                null,
                InterviewFormat.TECHNICAL,
                futureAt(20, 14, 0),
                60,
                true
        );

        String expected = "https://meet.jit.si/interviewpartner-" + created.getId();
        assertThat(created.getVideoMeetingUrl()).isEqualTo(expected);
        assertThat(interviewRepository.findById(created.getId()).orElseThrow().getVideoMeetingUrl())
                .isEqualTo(expected);
    }

    @Test
    void createInterview_solo_doesNotSetVideoUrl() {
        User solo = saveUser(503L, "solo503");

        Interview created = interviewService.createInterview(
                solo.getId(),
                solo.getId(),
                Language.RUSSIAN,
                null,
                InterviewFormat.TECHNICAL,
                futureAt(21, 14, 0),
                60,
                true
        );

        assertThat(created.getVideoMeetingUrl()).isNull();
    }

    @Test
    void joinInterview_whenSoloBecomesPaired_setsJitsiUrl() {
        User owner = saveUser(601L, "owner601");
        User joiner = saveUser(602L, "join602");

        Interview solo = interviewRepository.saveAndFlush(Interview.builder()
                .candidate(owner)
                .interviewer(owner)
                .language(Language.RUSSIAN)
                .format(InterviewFormat.TECHNICAL)
                .dateTime(futureAt(22, 16, 0))
                .duration(60)
                .status(InterviewStatus.SCHEDULED)
                .initiatorIsCandidate(false)
                .build());

        Interview after = interviewService.joinInterview(solo.getId(), joiner.getId(), true);

        assertThat(after.getVideoMeetingUrl())
                .isEqualTo("https://meet.jit.si/interviewpartner-" + after.getId());
    }

    private User saveUser(long telegramId, String username) {
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
