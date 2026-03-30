package com.interviewpartner.bot.telegram;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBean(TelegramClient.class)
public class TelegramUpdateProcessor {

    private final CommandHandler commandHandler;
    private final TelegramClient telegramClient;

    public void process(Update update) {
        try {
            commandHandler.handle(update, telegramClient);
        } catch (Exception e) {
            log.error("Error handling update", e);
        }
    }
}
