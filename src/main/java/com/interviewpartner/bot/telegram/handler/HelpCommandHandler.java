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
            Справка

            • Записаться на собеседование — вы кандидат, вам подберут интервьюера.
            • Провести собеседование — вы интервьюер, проводите встречу с кандидатом.
            • Расписание — календарь ваших запланированных собеседований по ролям.
            • Помощь — эта подсказка.""";

    @Override
    public boolean canHandle(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return false;
        }
        String text = update.getMessage().getText().strip();
        return text.startsWith(CMD) || text.equalsIgnoreCase(ChatMenuKeyboardBuilder.BTN_HELP);
    }

    @Override
    public void handle(Update update, TelegramClient telegramClient) {
        try {
            telegramClient.execute(SendMessage.builder()
                    .chatId(update.getMessage().getChatId())
                    .text(MESSAGE_RU)
                    .replyMarkup(ChatMenuKeyboardBuilder.buildPersistentKeyboard())
                    .build());
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        }
    }
}
