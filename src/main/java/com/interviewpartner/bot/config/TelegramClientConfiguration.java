package com.interviewpartner.bot.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Configuration
public class TelegramClientConfiguration {

    @Bean
    @ConditionalOnExpression(
            "environment.getProperty('telegram.bot.token') != null && !environment.getProperty('telegram.bot.token').isBlank()")
    public TelegramClient telegramClient(TelegramBotProperties botProperties) {
        return new OkHttpTelegramClient(botProperties.getToken());
    }
}
