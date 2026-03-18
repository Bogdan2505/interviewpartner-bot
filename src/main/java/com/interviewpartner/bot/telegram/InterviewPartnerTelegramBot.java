package com.interviewpartner.bot.telegram;

import com.interviewpartner.bot.config.TelegramBotProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Slf4j
@Component
@ConditionalOnExpression("environment.getProperty('telegram.bot.token') != null && !environment.getProperty('telegram.bot.token').isBlank()")
public class InterviewPartnerTelegramBot implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private final TelegramBotProperties botProperties;
    private final CommandHandler commandHandler;
    private final TelegramClient telegramClient;

    public InterviewPartnerTelegramBot(TelegramBotProperties botProperties,
                                      CommandHandler commandHandler) {
        this.botProperties = botProperties;
        this.commandHandler = commandHandler;
        this.telegramClient = new OkHttpTelegramClient(botProperties.getToken());
    }

    @Override
    public String getBotToken() {
        return botProperties.getToken();
    }

    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() {
        return this;
    }

    @Override
    public void consume(Update update) {
        try {
            commandHandler.handle(update, telegramClient);
        } catch (Exception e) {
            log.error("Error handling update", e);
        }
    }
}
