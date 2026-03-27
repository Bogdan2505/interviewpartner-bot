package com.interviewpartner.bot.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "telegram.bot")
public class TelegramBotProperties {

    private String token = "";
    private String username = "";
    /** Режим webhook (prod на Fly); иначе long polling. */
    private Webhook webhook = new Webhook();

    @Getter
    @Setter
    public static class Webhook {
        private boolean enabled = false;
        /** Публичный базовый URL, например https://interviewpartner-bot.fly.dev (без / в конце). */
        private String baseUrl = "";
        /** Секрет для заголовка X-Telegram-Bot-Api-Secret-Token и SetWebhook. */
        private String secret = "";
    }
}
