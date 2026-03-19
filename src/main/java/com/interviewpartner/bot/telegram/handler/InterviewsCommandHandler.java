package com.interviewpartner.bot.telegram.handler;

import com.interviewpartner.bot.model.Interview;
import com.interviewpartner.bot.model.InterviewStatus;
import com.interviewpartner.bot.model.User;
import com.interviewpartner.bot.service.InterviewService;
import com.interviewpartner.bot.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Order(22)
@Component
@RequiredArgsConstructor
public class InterviewsCommandHandler implements BotCommandHandler {

    private static final String CMD = "/interviews";
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final UserService userService;
    private final InterviewService interviewService;

    @Override
    public boolean canHandle(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return false;
        }
        String text = update.getMessage().getText().strip();
        return text.startsWith(CMD) || text.equalsIgnoreCase(ChatMenuKeyboardBuilder.BTN_MY_INTERVIEWS);
    }

    @Override
    public void handle(Update update, TelegramClient telegramClient) {
        var message = update.getMessage();
        Long chatId = message.getChatId();
        var from = message.getFrom();
        if (from == null) {
            send(chatId, "Ошибка: не удалось определить пользователя.", telegramClient);
            return;
        }
        String username = from.getUserName() != null ? from.getUserName() : from.getFirstName();
        User user = userService.registerUser(from.getId(), username != null ? username : "user");

        List<Interview> all = interviewService.getUserInterviews(user.getId(), null);
        var now = LocalDateTime.now();
        var upcoming = all.stream().filter(i -> i.getDateTime().isAfter(now) && i.getStatus() == InterviewStatus.SCHEDULED).toList();
        var past = all.stream().filter(i -> i.getDateTime().isBefore(now) || i.getStatus() != InterviewStatus.SCHEDULED).toList();

        String text = render(upcoming, past);
        try {
            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .replyMarkup(ChatMenuKeyboardBuilder.buildPersistentKeyboard())
                    .build());
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        }
    }

    private static void send(Long chatId, String text, TelegramClient telegramClient) {
        try {
            telegramClient.execute(SendMessage.builder().chatId(chatId).text(text).build());
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        }
    }

    private static String render(List<Interview> upcoming, List<Interview> past) {
        StringBuilder sb = new StringBuilder();
        sb.append("Ваши собеседования\n\n");
        sb.append("Предстоящие:\n");
        if (upcoming.isEmpty()) {
            sb.append("- нет\n");
        } else {
            for (Interview i : upcoming) {
                sb.append("- ").append(DT.format(i.getDateTime()))
                        .append(" • ").append(i.getLanguage())
                        .append(" • ").append(i.getFormat())
                        .append(" • ").append(i.getDuration()).append(" мин")
                        .append("\n");
            }
        }
        sb.append("\nПрошедшие/отменённые:\n");
        if (past.isEmpty()) {
            sb.append("- нет\n");
        } else {
            for (Interview i : past) {
                sb.append("- ").append(DT.format(i.getDateTime()))
                        .append(" • ").append(i.getStatus())
                        .append(" • ").append(i.getLanguage())
                        .append(" • ").append(i.getFormat())
                        .append("\n");
            }
        }
        return sb.toString();
    }
}
