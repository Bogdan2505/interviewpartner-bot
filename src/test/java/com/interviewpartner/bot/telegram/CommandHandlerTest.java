package com.interviewpartner.bot.telegram;

import com.interviewpartner.bot.telegram.handler.BotCommandHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Тесты маршрутизации команд: вызов правильного обработчика для /start, /help и неизвестной команды.
 */
class CommandHandlerTest {

    private CommandHandler commandHandler;
    private TelegramClient telegramClient;
    private BotCommandHandler startHandler;
    private BotCommandHandler unknownHandler;

    @BeforeEach
    void setUp() {
        startHandler = mock(BotCommandHandler.class);
        unknownHandler = mock(BotCommandHandler.class);

        whenCanHandle(startHandler, update -> {
            if (!update.hasMessage() || !update.getMessage().hasText()) return false;
            String text = update.getMessage().getText().strip();
            return text.equals("/start") || text.startsWith("/start ");
        });
        whenCanHandle(unknownHandler, update -> update.hasMessage() && update.getMessage().hasText());

        commandHandler = new CommandHandler(new ArrayList<>(List.of(startHandler, unknownHandler)));
        commandHandler.init(); // sort by @Order: start before unknown
        telegramClient = mock(TelegramClient.class);
    }

    private void whenCanHandle(BotCommandHandler handler, java.util.function.Predicate<Update> canHandle) {
        org.mockito.Mockito.when(handler.canHandle(any())).thenAnswer(inv -> canHandle.test(inv.getArgument(0)));
    }

    @Test
    void shouldRouteStartToStartHandler() {
        Update update = messageUpdate("/start");

        commandHandler.handle(update, telegramClient);

        verify(startHandler).handle(update, telegramClient);
    }

    @Test
    void shouldRouteUnknownTextToUnknownHandler() {
        Update update = messageUpdate("привет");

        commandHandler.handle(update, telegramClient);

        verify(unknownHandler).handle(update, telegramClient);
    }

    private static Update messageUpdate(String text) {
        Update update = mock(Update.class);
        Message message = mock(Message.class);
        when(update.hasMessage()).thenReturn(true);
        when(update.getMessage()).thenReturn(message);
        when(message.hasText()).thenReturn(true);
        when(message.getText()).thenReturn(text);
        when(message.getChatId()).thenReturn(1L);
        return update;
    }
}
