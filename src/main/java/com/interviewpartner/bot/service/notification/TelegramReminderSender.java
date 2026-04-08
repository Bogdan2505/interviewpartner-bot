package com.interviewpartner.bot.service.notification;

import com.interviewpartner.bot.config.TelegramBotProperties;
import com.interviewpartner.bot.model.Interview;
import com.interviewpartner.bot.model.ReminderType;
import com.interviewpartner.bot.model.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.Locale;

@Component
@ConditionalOnExpression("environment.getProperty('telegram.bot.token') != null && !environment.getProperty('telegram.bot.token').isBlank()")
public class TelegramReminderSender implements ReminderSender {

    private static final DateTimeFormatter DT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (VV)", Locale.ROOT);

    private final TelegramBotProperties botProperties;
    private final String jitsiMeetBaseUrl;
    private final ZoneId applicationZoneId;

    public TelegramReminderSender(
            TelegramBotProperties botProperties,
            @Value("${video-meeting.jitsi-base-url:https://meet.jit.si}") String jitsiMeetBaseUrl,
            ZoneId applicationZoneId
    ) {
        this.botProperties = botProperties;
        this.jitsiMeetBaseUrl = jitsiMeetBaseUrl.endsWith("/")
                ? jitsiMeetBaseUrl.substring(0, jitsiMeetBaseUrl.length() - 1)
                : jitsiMeetBaseUrl;
        this.applicationZoneId = applicationZoneId;
    }

    @Override
    public void sendReminder(Interview interview, ReminderType type) {
        TelegramClient client = new OkHttpTelegramClient(botProperties.getToken());
        String reminderText = switch (type) {
            case HOURS_24 -> "Напоминание: до собеседования 24 часа.";
            case HOURS_1 -> "Напоминание: до собеседования 1 час.";
            case MINUTES_15 -> "Напоминание: до собеседования 15 минут.";
            case START -> "Собеседование начинается сейчас.";
        } + "\nДата/время: " + interview.getDateTime().atZone(applicationZoneId).format(DT);
        String joinUrl = resolveMeetingUrl(interview);
        User candidate = interview.getCandidate();
        User interviewer = interview.getInterviewer();

        try {
            LinkedHashSet<Long> chatIds = new LinkedHashSet<>();
            chatIds.add(candidate.getTelegramId());
            chatIds.add(interviewer.getTelegramId());

            for (Long chatId : chatIds) {
                User partner = chatId.equals(candidate.getTelegramId()) ? interviewer : candidate;
                String text = reminderText
                        + "\nПартнёр: " + formatUserLabel(partner);
                if (joinUrl != null && !joinUrl.isBlank()) {
                    text += "\n\nСсылка на встречу (Jitsi Meet, хорошо работает в Chrome и Yandex):\n" + joinUrl;
                }
                client.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text(text)
                        .build());
            }
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        }
    }

    private static String formatUserLabel(User user) {
        if (user == null) {
            return "неизвестный пользователь";
        }
        String username = user.getUsername();
        if (username != null && !username.isBlank()) {
            return "@" + username;
        }
        if (user.getFirstName() != null && !user.getFirstName().isBlank()) {
            return user.getFirstName();
        }
        Long id = user.getId();
        return id != null ? "пользователь #" + id : "неизвестный пользователь";
    }

    /**
     * Совпадает с {@link com.interviewpartner.bot.service.InterviewServiceImpl}: в БД ссылка может быть пустой
     * для «solo» (один и тот же user как кандидат и интервьюер), пока не вызван attachJitsiIfPaired.
     */
    private String resolveMeetingUrl(Interview interview) {
        String existing = interview.getVideoMeetingUrl();
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        Long id = interview.getId();
        if (id == null) {
            return null;
        }
        return jitsiMeetBaseUrl + "/interviewpartner-" + id;
    }
}

