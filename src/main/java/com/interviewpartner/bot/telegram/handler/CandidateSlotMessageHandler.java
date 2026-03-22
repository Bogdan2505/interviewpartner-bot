package com.interviewpartner.bot.telegram.handler;

import com.interviewpartner.bot.exception.ScheduleOverlapException;
import com.interviewpartner.bot.service.CandidateSlotService;
import com.interviewpartner.bot.telegram.flow.CandidateSlotState;
import com.interviewpartner.bot.telegram.flow.ConversationStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

@Order(18)
@Component
@RequiredArgsConstructor
public class CandidateSlotMessageHandler implements BotCommandHandler {

    private static final DateTimeFormatter T = DateTimeFormatter.ofPattern("HH:mm");

    private final ConversationStateService stateService;
    private final CandidateSlotService candidateSlotService;

    @Override
    public boolean canHandle(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) return false;
        if (ChatMenuKeyboardBuilder.isMenuButton(update.getMessage().getText())) return false;
        var stateOpt = stateService.getCandidateSlot(update.getMessage().getChatId());
        if (stateOpt.isEmpty() || stateOpt.get().step != CandidateSlotState.Step.ADD_TIME) return false;
        var s = stateOpt.get();
        return s.dayOfWeek != null || (s.selectedDaysForSlot != null && !s.selectedDaysForSlot.isEmpty());
    }

    @Override
    public void handle(Update update, TelegramClient telegramClient) {
        Long chatId = update.getMessage().getChatId();
        var stateOpt = stateService.getCandidateSlot(chatId);
        if (stateOpt.isEmpty()) return;
        var state = stateOpt.get();
        if (state.step != CandidateSlotState.Step.ADD_TIME) return;
        boolean multiDay = state.selectedDaysForSlot != null && !state.selectedDaysForSlot.isEmpty();
        if (state.dayOfWeek == null && !multiDay) return;

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
        if (state.language == null) {
            send(chatId, "Сначала укажите направление.", telegramClient);
            return;
        }
        try {
            if (multiDay) {
                int added = 0;
                DayOfWeek failedDay = null;
                for (DayOfWeek d : state.selectedDaysForSlot) {
                    try {
                        candidateSlotService.addSlot(state.userId, state.language, d, start, end);
                        added++;
                    } catch (ScheduleOverlapException e) {
                        failedDay = d;
                        break;
                    }
                }
                if (failedDay != null) {
                    send(chatId, "Добавлено для части дней. В " + failedDay + " это время пересекается с существующим — введите другое время.", telegramClient);
                } else {
                    stateService.clearCandidateSlot(chatId);
                    send(chatId, "Слоты добавлены: " + start + "–" + end + " для " + added + " дн.", telegramClient);
                }
            } else {
                candidateSlotService.addSlot(state.userId, state.language, state.dayOfWeek, start, end);
                stateService.clearCandidateSlot(chatId);
                send(chatId, "Слот добавлен: " + state.dayOfWeek + " " + start + "-" + end, telegramClient);
            }
        } catch (ScheduleOverlapException e) {
            if (!multiDay) send(chatId, "Этот слот пересекается с существующим. Попробуйте другое время.", telegramClient);
        } catch (IllegalArgumentException e) {
            send(chatId, "Конец должен быть позже начала. Попробуйте ещё раз.", telegramClient);
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
