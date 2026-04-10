package com.interviewpartner.bot.telegram.handler;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.util.List;

/**
 * Сборка главного меню бота (текст приветствия и клавиатура).
 * Используется в /start и после действий по callback.
 */
public final class MainMenuBuilder {

    private static final String WELCOME_RU = """
            Добро пожаловать в InterviewPartner Bot!
            Здесь вы договариваетесь о взаимной практике: один час — две половины по ~30 минут, в первой половине роли «интервьюер / кандидат» заданы явно, во второй вы меняетесь.
            Выберите действие в меню ниже.""";

    private MainMenuBuilder() {
    }

    public static String getWelcomeText() {
        return WELCOME_RU;
    }

    /** Короткий текст после действия (без полного приветствия). */
    public static String getShortMenuPrompt() {
        return "Выберите действие в меню.";
    }

    public static InlineKeyboardMarkup buildMainMenuKeyboard() {
        var createInterview = InlineKeyboardButton.builder()
                .text("Записаться на собеседование")
                .callbackData("cmd:create_interview")
                .build();
        var availableSlots = InlineKeyboardButton.builder()
                .text("Доступные слоты")
                .callbackData("cmd:available_slots")
                .build();
        var schedule = InlineKeyboardButton.builder()
                .text("Расписание")
                .callbackData("cmd:schedule")
                .build();
        var help = InlineKeyboardButton.builder()
                .text("Справка")
                .callbackData("cmd:help")
                .build();

        List<InlineKeyboardRow> rows = List.of(
                new InlineKeyboardRow(createInterview),
                new InlineKeyboardRow(availableSlots),
                new InlineKeyboardRow(schedule),
                new InlineKeyboardRow(help));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }
}
