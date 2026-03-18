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

@Order(20)
@Component
@RequiredArgsConstructor
public class CreateInterviewCommandHandler implements BotCommandHandler {

    private static final String CMD = "/create_interview";
    private static final String MESSAGE_RU = "Создание собеседования: выберите язык.";

    private final ConversationStateService stateService;
    private final UserService userService;

    @Override
    public boolean canHandle(Update update) {
        return hasCommand(update, CMD);
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
        stateService.startCreateInterview(chatId, user.getId());
        send(chatId, MESSAGE_RU, telegramClient, buildLanguageKeyboard());
    }

    private static boolean hasCommand(Update update, String cmd) {
        return update.hasMessage() && update.getMessage().hasText()
                && update.getMessage().getText().strip().startsWith(cmd);
    }

    private static void send(Long chatId, String text, TelegramClient telegramClient, InlineKeyboardMarkup markup) {
        try {
            var builder = SendMessage.builder().chatId(chatId).text(text);
            if (markup != null) {
                builder.replyMarkup(markup);
            }
            telegramClient.execute(builder.build());
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        }
    }

    private static InlineKeyboardMarkup buildLanguageKeyboard() {
        var ru = InlineKeyboardButton.builder().text("Русский").callbackData("ci:lang:RUSSIAN").build();
        var en = InlineKeyboardButton.builder().text("English").callbackData("ci:lang:ENGLISH").build();
        var cancel = InlineKeyboardButton.builder().text("Отмена").callbackData("ci:cancel").build();
        List<InlineKeyboardRow> rows = List.of(
                new InlineKeyboardRow(ru, en),
                new InlineKeyboardRow(cancel)
        );
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }
}
