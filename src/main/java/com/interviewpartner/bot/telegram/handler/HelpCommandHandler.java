package com.interviewpartner.bot.telegram.handler;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Order(24)
@Component
public class HelpCommandHandler implements BotCommandHandler {

    private static final String CMD = "/help";
    private static final String MESSAGE_RU = """
            Команды бота:
            /start — начать работу, главное меню
            /create_interview — создать собеседование
            /find_partner — найти партнёра
            /interviews — мои собеседования
            /schedule — моё расписание
            /help — эта справка""";

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
