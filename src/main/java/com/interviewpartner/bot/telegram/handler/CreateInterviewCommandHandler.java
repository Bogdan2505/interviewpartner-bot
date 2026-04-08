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
 * «Записаться на собеседование» — выбор языка → календарь → слоты (взаимный час).
 */
@Order(20)
@Component
@RequiredArgsConstructor
public class CreateInterviewCommandHandler implements BotCommandHandler {

    private final ConversationStateService stateService;
    private final UserService userService;

    @Override
    public boolean canHandle(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) return false;
        String text = update.getMessage().getText().strip();
        return text.equalsIgnoreCase(ChatMenuKeyboardBuilder.BTN_CREATE_INTERVIEW);
    }

    @Override
    public void handle(Update update, TelegramClient telegramClient) {
        var message = update.getMessage();
        Long chatId = message.getChatId();
        var from = message.getFrom();
        if (from == null) {
            send(chatId, "Ошибка: не удалось определить пользователя.", telegramClient, null);
            return;
        }
        Long telegramId = from.getId();
        String username = from.getUserName() != null ? from.getUserName() : from.getFirstName();
        User user = userService.registerUser(telegramId, username != null ? username : "user");
        // Сброс явный: иначе старые inline-сообщения в чате продолжают слать ci:* в «новую» сессию без языка.
        stateService.clearCreateInterview(chatId);
        stateService.startCreateInterview(chatId, user.getId());
        send(chatId, "Записаться на взаимный час: выберите направление.", telegramClient, buildLanguageKeyboard());
    }

    private static void send(Long chatId, String text, TelegramClient telegramClient, InlineKeyboardMarkup markup) {
        try {
            var builder = SendMessage.builder().chatId(chatId).text(text);
            if (markup != null) builder.replyMarkup(markup);
            telegramClient.execute(builder.build());
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        }
    }

    private static InlineKeyboardMarkup buildLanguageKeyboard() {
        return InlineKeyboardMarkup.builder().keyboard(List.of(
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("Java").callbackData("ci:lang:JAVA").build(),
                        InlineKeyboardButton.builder().text("C#").callbackData("ci:lang:CSHARP").build()),
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("Python").callbackData("ci:lang:PYTHON").build(),
                        InlineKeyboardButton.builder().text("Product Manager").callbackData("ci:lang:PRODUCT_MANAGER").build(),
                        InlineKeyboardButton.builder().text("JavaScript").callbackData("ci:lang:JAVASCRIPT").build()),
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("Kotlin").callbackData("ci:lang:KOTLIN").build(),
                        InlineKeyboardButton.builder().text("Swift").callbackData("ci:lang:SWIFT").build()),
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("Go").callbackData("ci:lang:GO").build(),
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
