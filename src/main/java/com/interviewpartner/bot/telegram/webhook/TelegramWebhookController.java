package com.interviewpartner.bot.telegram.webhook;

import com.interviewpartner.bot.config.TelegramBotProperties;
import com.interviewpartner.bot.telegram.TelegramUpdateProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Slf4j
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(value = "telegram.bot.webhook.enabled", havingValue = "true")
@ConditionalOnBean({TelegramClient.class, TelegramUpdateProcessor.class})
public class TelegramWebhookController {

    private static final String SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token";

    private final TelegramBotProperties botProperties;
    private final TelegramUpdateProcessor updateProcessor;

    @PostMapping("/telegram/webhook")
    public ResponseEntity<Void> webhook(
            @RequestHeader(value = SECRET_HEADER, required = false) String secretHeader,
            @RequestBody Update update) {
        String expected = botProperties.getWebhook().getSecret();
        if (!StringUtils.hasText(expected)) {
            log.warn("Webhook rejected: webhook secret not configured");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] headerBytes = (secretHeader != null ? secretHeader : "").getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedBytes, headerBytes)) {
            log.warn("Webhook rejected: invalid or missing secret header");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (update == null) {
            return ResponseEntity.badRequest().build();
        }
        updateProcessor.process(update);
        return ResponseEntity.ok().build();
    }
}
