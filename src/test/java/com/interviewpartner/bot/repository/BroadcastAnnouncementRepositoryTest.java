package com.interviewpartner.bot.repository;

import com.interviewpartner.bot.model.BroadcastAnnouncement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class BroadcastAnnouncementRepositoryTest {

    @Autowired
    private BroadcastAnnouncementRepository broadcastAnnouncementRepository;

    @Test
    void findDueForBroadcast_filtersByEnabledScheduledAndSent() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 1, 12, 0);

        broadcastAnnouncementRepository.save(BroadcastAnnouncement.builder()
                .messageText("past due")
                .scheduledAt(now.minusHours(1))
                .enabled(true)
                .build());
        broadcastAnnouncementRepository.save(BroadcastAnnouncement.builder()
                .messageText("future")
                .scheduledAt(now.plusHours(1))
                .enabled(true)
                .build());
        broadcastAnnouncementRepository.save(BroadcastAnnouncement.builder()
                .messageText("disabled")
                .scheduledAt(now.minusHours(1))
                .enabled(false)
                .build());
        BroadcastAnnouncement sent = broadcastAnnouncementRepository.save(BroadcastAnnouncement.builder()
                .messageText("already sent")
                .scheduledAt(now.minusHours(1))
                .enabled(true)
                .build());
        sent.setSentAt(now.minusMinutes(5));
        broadcastAnnouncementRepository.save(sent);

        List<BroadcastAnnouncement> due = broadcastAnnouncementRepository.findDueForBroadcast(now);

        assertThat(due).hasSize(1);
        assertThat(due.get(0).getMessageText()).isEqualTo("past due");
    }
}
