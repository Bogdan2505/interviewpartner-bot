package com.interviewpartner.bot.telegram.handler;

import com.interviewpartner.bot.service.UserService;
import com.interviewpartner.bot.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Order(10)
@Component
@RequiredArgsConstructor
public class StartCommandHandler implements BotCommandHandler {

    private static final String CMD_START = "/start";

    private final UserService userService;

    @Override
    public boolean canHandle(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return false;
        }
        String text = update.getMessage().getText().strip();
        return text.equals(CMD_START) || text.startsWith(CMD_START + " ");
    }

    @Override
    public void handle(Update update, TelegramClient telegramClient) {
        var message = update.getMessage();
        var chatId = message.getChatId();
        var from = message.getFrom();
        if (from == null) {
            return;
        }
        Long telegramId = from.getId();
        String username = from.getUserName() != null ? from.getUserName() : from.getFirstName();

        User user = userService.registerUser(telegramId, username != null ? username : "user");

        SendMessage send = SendMessage.builder()
                .chatId(chatId)
                .text(MainMenuBuilder.getWelcomeText())
                .replyMarkup(ChatMenuKeyboardBuilder.buildPersistentKeyboard())
                .build();
        try {
            telegramClient.execute(send);
        } catch (TelegramApiException e) {
            throw new RuntimeException("Failed to send /start reply", e);
        }
    }

}
