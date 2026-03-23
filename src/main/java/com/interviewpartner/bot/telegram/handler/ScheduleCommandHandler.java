package com.interviewpartner.bot.telegram.handler;

import com.interviewpartner.bot.model.User;
import com.interviewpartner.bot.service.InterviewService;
import com.interviewpartner.bot.service.UserService;
import com.interviewpartner.bot.telegram.flow.ConversationStateService;
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

import java.util.List;

@Order(23)
@Component
@RequiredArgsConstructor
public class ScheduleCommandHandler implements BotCommandHandler {

    private static final String CMD = "/schedule";
    private static final String IC_PREFIX = "ic:";

    private final UserService userService;
    private final ConversationStateService stateService;

    @Override
    public boolean canHandle(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return false;
        }
        String text = update.getMessage().getText().strip();
        return text.startsWith(CMD) || text.equalsIgnoreCase(ChatMenuKeyboardBuilder.BTN_SCHEDULE);
    }

    @Override
    public void handle(Update update, TelegramClient telegramClient) {
        try {
            var message = update.getMessage();
            Long chatId = message.getChatId();
            var from = message.getFrom();
            if (from == null) {
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text("Ошибка: не удалось определить пользователя.")
                        .build());
                return;
            }

            String username = from.getUserName() != null ? from.getUserName() : from.getFirstName();
            User user = userService.registerUser(from.getId(), username != null ? username : "user");

            stateService.startInterviewCalendar(chatId, user.getId());

            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("Расписание — выберите раздел:")
                    .replyMarkup(buildFilterSelectionKeyboard())
                    .build());
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        }
    }

    public static InlineKeyboardMarkup buildFilterSelectionKeyboard() {
        return InlineKeyboardMarkup.builder().keyboard(List.of(
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder()
                                .text("Заявки на собеседования")
                                .callbackData(IC_PREFIX + "filter:PENDING")
                                .build()
                ),
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder()
                                .text("Согласованные собеседования")
                                .callbackData(IC_PREFIX + "filter:CONFIRMED")
                                .build()
                ),
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("Закрыть").callbackData(IC_PREFIX + "close").build()
                )
        )).build();
    }
}
