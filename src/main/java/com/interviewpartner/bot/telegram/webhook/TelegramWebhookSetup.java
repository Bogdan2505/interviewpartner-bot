package com.interviewpartner.bot.telegram.webhook;

import com.interviewpartner.bot.config.TelegramBotProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.telegram.telegrambots.meta.api.methods.updates.SetWebhook;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Slf4j
@Component
@Order(100)
@RequiredArgsConstructor
@ConditionalOnProperty(value = "telegram.bot.webhook.enabled", havingValue = "true")
@ConditionalOnBean(TelegramClient.class)
public class TelegramWebhookSetup implements ApplicationRunner {

    private final TelegramBotProperties botProperties;
    private final TelegramClient telegramClient;

    @Override
    public void run(ApplicationArguments args) throws TelegramApiException {
        var wh = botProperties.getWebhook();
        if (!StringUtils.hasText(wh.getSecret())) {
            throw new IllegalStateException(
                    "Задайте telegram.bot.webhook.secret (на Fly: fly secrets set TELEGRAM_BOT_WEBHOOK_SECRET=...)");
        }
        if (!StringUtils.hasText(wh.getBaseUrl())) {
            throw new IllegalStateException(
                    "Задайте telegram.bot.webhook.base-url (публичный URL без завершающего /)");
        }
        String url = wh.getBaseUrl().replaceAll("/+$", "") + "/telegram/webhook";
        SetWebhook setWebhook =
                SetWebhook.builder().url(url).secretToken(wh.getSecret()).build();
        Boolean ok = telegramClient.execute(setWebhook);
        log.info("Telegram SetWebhook url={} ok={}", url, ok);
    }
}
