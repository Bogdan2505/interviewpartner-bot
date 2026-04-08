package com.interviewpartner.bot.telegram.handler;

import com.interviewpartner.bot.model.User;
import com.interviewpartner.bot.service.InterviewService;
import com.interviewpartner.bot.telegram.flow.ConversationStateService;
import com.interviewpartner.bot.telegram.flow.CreateInterviewState;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Обрабатывает текстовые ответы пользователя в процессе /create_interview.
 */
@Order(19)
@Component
@RequiredArgsConstructor
public class CreateInterviewMessageHandler implements BotCommandHandler {

    private static final DateTimeFormatter DT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ConversationStateService stateService;
    private final InterviewService interviewService;
    private final Clock clock;

    @Override
    public boolean canHandle(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return false;
        }
        if (ChatMenuKeyboardBuilder.isMenuButton(update.getMessage().getText())) {
            return false;
        }
        Long chatId = update.getMessage().getChatId();
        return stateService.getCreateInterview(chatId).isPresent();
    }

    @Override
    public void handle(Update update, TelegramClient telegramClient) {
        Long chatId = update.getMessage().getChatId();
        var text = update.getMessage().getText().strip();
        var stateOpt = stateService.getCreateInterview(chatId);
        if (stateOpt.isEmpty()) {
            return;
        }
        var state = stateOpt.get();

        try {
            if (state.step == CreateInterviewState.Step.DATE_TIME) {
                LocalDateTime dt;
                try {
                    dt = LocalDateTime.parse(text, DT_FORMAT);
                } catch (DateTimeParseException e) {
                    telegramClient.execute(SendMessage.builder()
                            .chatId(chatId)
                            .text("Неверный формат. Введите дату и время как yyyy-MM-dd HH:mm (например 2026-03-25 19:00), часовой пояс: "
                                    + clock.getZone().getId())
                            .build());
                    return;
                }
                if (dt.isBefore(LocalDateTime.now(clock))) {
                    telegramClient.execute(SendMessage.builder()
                            .chatId(chatId)
                            .text("Нельзя выбрать дату и время в прошлом. Укажите момент не раньше текущего.")
                            .build());
                    return;
                }
                state.dateTime = dt;
                state.step = CreateInterviewState.Step.DURATION;
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text("Введите длительность в минутах (например 30 или 60).")
                        .build());
                return;
            }

            if (state.step == CreateInterviewState.Step.DURATION) {
                int duration;
                try {
                    duration = Integer.parseInt(text);
                } catch (NumberFormatException e) {
                    telegramClient.execute(SendMessage.builder()
                            .chatId(chatId)
                            .text("Введите число минут, например 30 или 60.")
                            .build());
                    return;
                }
                if (duration <= 0 || duration > 240) {
                    telegramClient.execute(SendMessage.builder()
                            .chatId(chatId)
                            .text("Длительность должна быть от 1 до 240 минут.")
                            .build());
                    return;
                }
                state.durationMinutes = duration;
                state.step = CreateInterviewState.Step.PARTNER;
                Long myId = state.candidateUserId;
                List<User> partners = interviewService.findAvailablePartners(myId, state.language, state.dateTime);
                String partnerText = partners.isEmpty()
                        ? "Нет доступных партнёров по выбранным параметрам. Создать открытый часовой слот без партнёра?"
                        : "Выберите партнёра для взаимного часа:";
                InlineKeyboardMarkup partnerKb = buildPartnerKeyboard(partners);
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text(partnerText)
                        .replyMarkup(partnerKb)
                        .build());
            }
        } catch (TelegramApiException e) {
            throw new RuntimeException("Failed to handle create_interview message", e);
        }
    }

    private static InlineKeyboardMarkup buildPartnerKeyboard(List<User> partners) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (User u : partners) {
            String label = u.getUsername() != null ? "@" + u.getUsername() : ("User " + u.getId());
            rows.add(new InlineKeyboardRow(
                    InlineKeyboardButton.builder().text(label).callbackData("ci:partner:" + u.getId()).build()));
        }
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("Создать слот без партнёра").callbackData("ci:partner:self").build()));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private static InlineKeyboardMarkup confirmKeyboard() {
        var yes = InlineKeyboardButton.builder().text("Создать").callbackData("ci:confirm:yes").build();
        var no = InlineKeyboardButton.builder().text("Отмена").callbackData("ci:confirm:no").build();
        List<InlineKeyboardRow> rows = List.of(new InlineKeyboardRow(yes, no));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }
}

