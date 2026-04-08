package com.interviewpartner.bot.telegram.handler;

import com.interviewpartner.bot.model.User;
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

/**
 * "Провести собеседование" — запускает ci: поток как интервьюер (выбор языка → календарь → слоты).
 */
@Order(21)
@Component
@RequiredArgsConstructor
public class FindPartnerCommandHandler implements BotCommandHandler {

    private final ConversationStateService stateService;
    private final UserService userService;

    @Override
    public boolean canHandle(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) return false;
        String text = update.getMessage().getText().strip();
        return text.equalsIgnoreCase(ChatMenuKeyboardBuilder.BTN_FIND_PARTNER);
    }

    @Override
    public void handle(Update update, TelegramClient telegramClient) {
        try {
            var message = update.getMessage();
            Long chatId = message.getChatId();
            var from = message.getFrom();
            if (from == null) {
                telegramClient.execute(SendMessage.builder().chatId(chatId).text("Ошибка: не удалось определить пользователя.").build());
                return;
            }
            Long telegramId = from.getId();
            String username = from.getUserName() != null ? from.getUserName() : from.getFirstName();
            User user = userService.registerUser(telegramId, username != null ? username : "user");
            stateService.startCreateInterview(chatId, user.getId(), false);

            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("Провести собеседование: выберите направление.")
                    .replyMarkup(languageKeyboard())
                    .build());
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        }
    }

    private static InlineKeyboardMarkup languageKeyboard() {
        return InlineKeyboardMarkup.builder().keyboard(List.of(
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("Java").callbackData("ci:lang:JAVA").build(),
                        InlineKeyboardButton.builder().text("C#").callbackData("ci:lang:CSHARP").build()),
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("Python").callbackData("ci:lang:PYTHON").build(),
                        InlineKeyboardButton.builder().text("Product Manager").callbackData("ci:lang:PRODUCT_MANAGER").build(),
                        InlineKeyboardButton.builder().text("JavaScript").callbackData("ci:lang:JAVASCRIPT").build(),
                        InlineKeyboardButton.builder().text("Kotlin").callbackData("ci:lang:KOTLIN").build()),
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("Swift").callbackData("ci:lang:SWIFT").build(),
                        InlineKeyboardButton.builder().text("Go").callbackData("ci:lang:GO").build()),
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("QA").callbackData("ci:lang:QA").build(),
                        InlineKeyboardButton.builder().text("Data Analytics").callbackData("ci:lang:DATA_ANALYTICS").build()),
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("Business Analysis").callbackData("ci:lang:BUSINESS_ANALYSIS").build(),
                        InlineKeyboardButton.builder().text("System Analysis").callbackData("ci:lang:SYSTEM_ANALYSIS").build()),
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("Отмена").callbackData("ci:cancel").build())
        )).build();
    }
}
