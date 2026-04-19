package com.interviewpartner.bot.service.notification;

import com.interviewpartner.bot.config.TelegramBotProperties;
import com.interviewpartner.bot.model.BroadcastAnnouncement;
import com.interviewpartner.bot.repository.BroadcastAnnouncementRepository;
import com.interviewpartner.bot.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Рассылка объявлений из {@code broadcast_announcements} всем пользователям из {@code users}.
 * Записи создаются/правятся в БД; отправка при enabled=true, sent_at IS NULL, scheduled_at &lt;= now.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnExpression("environment.getProperty('telegram.bot.token') != null && !environment.getProperty('telegram.bot.token').isBlank()")
public class BroadcastAnnouncementDispatchService {

    private static final int TELEGRAM_MAX_MESSAGE_LENGTH = 4096;

    private final BroadcastAnnouncementRepository announcementRepository;
    private final UserRepository userRepository;
    private final TelegramBotProperties botProperties;
    private final Clock clock;

    public boolean dispatchDue() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<BroadcastAnnouncement> due = announcementRepository.findDueForBroadcast(now);
        if (due.isEmpty()) {
            return false;
        }
        List<Long> chatIds = userRepository.findAllTelegramIds();
        if (chatIds.isEmpty()) {
            log.debug("Broadcast: нет пользователей в БД, рассылка отложена");
            return false;
        }

        TelegramClient client = new OkHttpTelegramClient(botProperties.getToken());
        for (BroadcastAnnouncement announcement : due) {
            dispatchOne(client, announcement, chatIds, now);
        }
        return true;
    }

    private void dispatchOne(
            TelegramClient client,
            BroadcastAnnouncement announcement,
            List<Long> chatIds,
            LocalDateTime now
    ) {
        String text = announcement.getMessageText();
        if (text == null || text.isBlank()) {
            log.warn("Пропуск рассылки id={}: пустой текст", announcement.getId());
            return;
        }
        if (text.length() > TELEGRAM_MAX_MESSAGE_LENGTH) {
            log.warn("Рассылка id={}: текст обрезан до {} символов (лимит Telegram)",
                    announcement.getId(), TELEGRAM_MAX_MESSAGE_LENGTH);
            text = text.substring(0, TELEGRAM_MAX_MESSAGE_LENGTH);
        }

        int failures = 0;
        for (Long chatId : chatIds) {
            try {
                client.execute(SendMessage.builder().chatId(chatId).text(text).build());
            } catch (TelegramApiException e) {
                failures++;
                log.warn("Рассылка id={}: не отправлено chatId={}, причина: {}",
                        announcement.getId(), chatId, e.getMessage());
            }
        }

        announcement.setSentAt(now);
        announcementRepository.save(announcement);
        log.info("Рассылка id={} завершена: получателей={}, ошибок отправки={}",
                announcement.getId(), chatIds.size(), failures);
    }
}
