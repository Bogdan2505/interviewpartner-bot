package com.interviewpartner.bot.telegram;

import com.interviewpartner.bot.telegram.handler.BotCommandHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import jakarta.annotation.PostConstruct;
import java.util.List;

/**
 * Маршрутизатор: передаёт входящие Update в подходящий BotCommandHandler.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommandHandler {

    private final List<BotCommandHandler> handlers;

    @PostConstruct
    public void init() {
        handlers.sort(AnnotationAwareOrderComparator.INSTANCE);
    }

    public void handle(Update update, TelegramClient telegramClient) {
        for (BotCommandHandler handler : handlers) {
            if (handler.canHandle(update)) {
                handler.handle(update, telegramClient);
                return;
            }
        }
        log.warn("No handler for update: message={}, callback={}",
                update.hasMessage(),
                update.hasCallbackQuery());
    }
}
