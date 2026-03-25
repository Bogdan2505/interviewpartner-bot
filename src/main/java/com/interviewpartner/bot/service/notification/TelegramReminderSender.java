package com.interviewpartner.bot.service.notification;

import com.interviewpartner.bot.config.TelegramBotProperties;
import com.interviewpartner.bot.model.Interview;
import com.interviewpartner.bot.model.ReminderType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.format.DateTimeFormatter;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnExpression("environment.getProperty('telegram.bot.token') != null && !environment.getProperty('telegram.bot.token').isBlank()")
public class TelegramReminderSender implements ReminderSender {

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final TelegramBotProperties botProperties;

    @Override
    public void sendReminder(Interview interview, ReminderType type) {
        TelegramClient client = new OkHttpTelegramClient(botProperties.getToken());
        String text = switch (type) {
            case HOURS_24 -> "Напоминание: до собеседования 24 часа.";
            case HOURS_1 -> "Напоминание: до собеседования 1 час.";
            case MINUTES_15 -> "Напоминание: до собеседования 15 минут.";
        } + "\nДата/время: " + DT.format(interview.getDateTime());
        String joinUrl = interview.getVideoMeetingUrl();
        if (joinUrl != null && !joinUrl.isBlank()) {
            text += "\n\nСсылка на встречу (Jitsi Meet, хорошо работает в Chrome и Yandex):\n" + joinUrl;
        }

        try {
            client.execute(SendMessage.builder()
                    .chatId(interview.getCandidate().getTelegramId())
                    .text(text)
                    .build());
            client.execute(SendMessage.builder()
                    .chatId(interview.getInterviewer().getTelegramId())
                    .text(text)
                    .build());
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        }
    }
}

