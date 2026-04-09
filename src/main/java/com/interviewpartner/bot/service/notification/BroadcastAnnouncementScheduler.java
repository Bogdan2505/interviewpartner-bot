package com.interviewpartner.bot.service.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Периодически проверяет таблицу {@code broadcast_announcements} и рассылает подходящие сообщения.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBean(BroadcastAnnouncementDispatchService.class)
public class BroadcastAnnouncementScheduler {

    private final BroadcastAnnouncementDispatchService dispatchService;

    @Scheduled(fixedRateString = "PT60S")
    public void tick() {
        try {
            dispatchService.dispatchDue();
        } catch (Exception e) {
            log.error("Ошибка при рассылке объявлений", e);
        }
    }
}
