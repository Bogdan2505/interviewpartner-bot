package com.interviewpartner.bot.telegram;

import com.interviewpartner.bot.config.TelegramBotProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.Update;

@Component
@ConditionalOnProperty(value = "telegram.bot.webhook.enabled", havingValue = "false", matchIfMissing = true)
@ConditionalOnExpression("environment.getProperty('telegram.bot.token') != null && !environment.getProperty('telegram.bot.token').isBlank()")
@ConditionalOnBean(TelegramUpdateProcessor.class)
public class InterviewPartnerTelegramBot implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private final TelegramBotProperties botProperties;
    private final TelegramUpdateProcessor updateProcessor;

    public InterviewPartnerTelegramBot(
            TelegramBotProperties botProperties, TelegramUpdateProcessor updateProcessor) {
        this.botProperties = botProperties;
        this.updateProcessor = updateProcessor;
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
        updateProcessor.process(update);
    }
}
