package com.interviewpartner.bot.telegram.handler;

import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Обработчик одной команды или типа обновления.
 */
public interface BotCommandHandler {

    /**
     * @return true, если этот обработчик должен обработать данное обновление
     */
    boolean canHandle(Update update);

    /**
     * Обработать обновление и отправить ответ через клиента.
     */
    void handle(Update update, TelegramClient telegramClient);
}
