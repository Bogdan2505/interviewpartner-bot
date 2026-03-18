package com.interviewpartner.bot.telegram.handler;

import com.interviewpartner.bot.exception.ScheduleOverlapException;
import com.interviewpartner.bot.service.ScheduleService;
import com.interviewpartner.bot.telegram.flow.ConversationStateService;
import com.interviewpartner.bot.telegram.flow.ScheduleState;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

@Order(17)
@Component
@RequiredArgsConstructor
public class ScheduleMessageHandler implements BotCommandHandler {

    private static final DateTimeFormatter T = DateTimeFormatter.ofPattern("HH:mm");

    private final ConversationStateService stateService;
    private final ScheduleService scheduleService;

    @Override
    public boolean canHandle(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return false;
        }
        return stateService.getSchedule(update.getMessage().getChatId()).isPresent();
    }

    @Override
    public void handle(Update update, TelegramClient telegramClient) {
        Long chatId = update.getMessage().getChatId();
        var stateOpt = stateService.getSchedule(chatId);
        if (stateOpt.isEmpty()) return;
        var state = stateOpt.get();
        if (state.step != ScheduleState.Step.ADD_TIME || state.dayOfWeek == null) return;

        String text = update.getMessage().getText().strip();
        var parts = text.split("-");
        if (parts.length != 2) {
            send(chatId, "Неверный формат. Введите HH:mm-HH:mm (например 10:00-12:00)", telegramClient);
            return;
        }
        LocalTime start;
        LocalTime end;
        try {
            start = LocalTime.parse(parts[0].trim(), T);
            end = LocalTime.parse(parts[1].trim(), T);
        } catch (DateTimeParseException e) {
            send(chatId, "Неверное время. Введите HH:mm-HH:mm (например 10:00-12:00)", telegramClient);
            return;
        }
        try {
            scheduleService.addAvailability(state.userId, state.dayOfWeek, start, end);
            send(chatId, "Слот добавлен: " + state.dayOfWeek + " " + start + "-" + end, telegramClient);
        } catch (ScheduleOverlapException e) {
            send(chatId, "Этот слот пересекается с существующим. Попробуйте другое время.", telegramClient);
        } catch (IllegalArgumentException e) {
            send(chatId, "Конец должен быть позже начала. Попробуйте ещё раз.", telegramClient);
        } finally {
            stateService.clearSchedule(chatId);
        }
    }

    private static void send(Long chatId, String text, TelegramClient telegramClient) {
        try {
            telegramClient.execute(SendMessage.builder().chatId(chatId).text(text).build());
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        }
    }
}

