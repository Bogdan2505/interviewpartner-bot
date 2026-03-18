package com.interviewpartner.bot.telegram.handler;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Order(22)
@Component
public class InterviewsCommandHandler implements BotCommandHandler {

    private static final String CMD = "/interviews";
    private static final String MESSAGE_RU = "Список предстоящих и прошедших собеседований (в разработке).";

    @Override
    public boolean canHandle(Update update) {
        return update.hasMessage() && update.getMessage().hasText()
                && update.getMessage().getText().strip().startsWith(CMD);
    }

    @Override
    public void handle(Update update, TelegramClient telegramClient) {
        try {
            telegramClient.execute(SendMessage.builder()
                    .chatId(update.getMessage().getChatId())
                    .text(MESSAGE_RU)
                    .build());
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        }
    }
}
