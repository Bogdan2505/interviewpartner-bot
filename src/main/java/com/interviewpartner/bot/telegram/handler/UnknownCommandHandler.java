package com.interviewpartner.bot.telegram.handler;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Обрабатывает неизвестные команды и любые текстовые сообщения без команды.
 * Должен быть зарегистрирован последним (наибольший @Order).
 */
@Order(100)
@Component
public class UnknownCommandHandler implements BotCommandHandler {

    private static final String MESSAGE_RU = "Неизвестная команда. Используйте /start для начала работы.";

    @Override
    public boolean canHandle(Update update) {
        return update.hasMessage() && update.getMessage().hasText();
    }

    @Override
    public void handle(Update update, TelegramClient telegramClient) {
        var chatId = update.getMessage().getChatId();
        SendMessage send = SendMessage.builder()
                .chatId(chatId)
                .text(MESSAGE_RU)
                .build();
        try {
            telegramClient.execute(send);
        } catch (TelegramApiException e) {
            throw new RuntimeException("Failed to send unknown command reply", e);
        }
    }
}
