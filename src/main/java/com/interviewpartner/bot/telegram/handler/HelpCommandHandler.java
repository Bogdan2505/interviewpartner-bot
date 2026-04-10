package com.interviewpartner.bot.telegram.handler;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Order(24)
@Component
public class HelpCommandHandler implements BotCommandHandler {

    private static final String CMD = "/help";

    /** Текст справки: команда /help, кнопка «Справка» и callback cmd:help. */
    public static final String HELP_TEXT = """
            Справка

            • Записаться на собеседование — выбрать язык и время: присоединиться к уже открытому слоту другого участника или создать свой слот. 
            Встреча длится 60 минут: первые ~30 минут один из вас в роли интервьюера, а другой кандидат, вторые ~30 минут — наоборот (одна ссылка Jitsi на весь час).
            
            • Доступные слоты — быстрый список будущих слотов от других участников по выбранному языку.
            Слоты сгруппированы по уровням Junior/Middle/Senior и отсортированы по дате и времени.
            Можно сразу записаться: нажмите слот → проверьте детали → подтвердите запись.
            
            • Расписание — окна, когда вы готовы к таким встречам, и календарь уже запланированных слотов.
        
            Как зайти на видеовстречу (Jitsi Meet):
            1) Откройте ссылку из бота в браузере (удобнее Chrome или Яндекс.Браузер).
            2) Если страница предложит войти через почту или аккаунт — войдите.
            3) При входе нажмите вариант, что вы организатор, если интерфейс это спрашивает. Если партнёр этого не сделал — сделайте это вы, чтобы у обоих было нормальное подключение.

            Если у вас есть пожелания, вы нашли баги или что-то осталось непонятным, напишите в чат:
            https://t.me/+cUV6Cy5mUvgyZDMy""";

    @Override
    public boolean canHandle(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return false;
        }
        String text = update.getMessage().getText().strip();
        return text.startsWith(CMD) || text.equalsIgnoreCase(ChatMenuKeyboardBuilder.BTN_HELP);
    }

    @Override
    public void handle(Update update, TelegramClient telegramClient) {
        try {
            telegramClient.execute(SendMessage.builder()
                    .chatId(update.getMessage().getChatId())
                    .text(HELP_TEXT)
                    .replyMarkup(ChatMenuKeyboardBuilder.buildPersistentKeyboard())
                    .build());
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        }
    }
}
