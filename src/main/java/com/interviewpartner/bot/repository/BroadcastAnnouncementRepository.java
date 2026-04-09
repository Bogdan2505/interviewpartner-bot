package com.interviewpartner.bot.repository;

import com.interviewpartner.bot.model.BroadcastAnnouncement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface BroadcastAnnouncementRepository extends JpaRepository<BroadcastAnnouncement, Long> {

    @Query("""
            select b from BroadcastAnnouncement b
            where b.enabled = true
              and b.sentAt is null
              and b.scheduledAt <= :now
            order by b.scheduledAt asc, b.id asc
            """)
    List<BroadcastAnnouncement> findDueForBroadcast(@Param("now") LocalDateTime now);
}
