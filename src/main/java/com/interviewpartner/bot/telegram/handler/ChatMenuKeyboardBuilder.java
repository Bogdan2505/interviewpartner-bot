package com.interviewpartner.bot.telegram.handler;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;

import java.util.List;

/**
 * Постоянная reply-клавиатура над полем ввода.
 */
public final class ChatMenuKeyboardBuilder {

    /** Я кандидат — хочу, чтобы меня собеседовали. */
    public static final String BTN_CREATE_INTERVIEW = "Записаться на собеседование";
    /** Я интервьюер — хочу проводить собеседование. */
    public static final String BTN_FIND_PARTNER = "Провести собеседование";
    /** Календарь уже запланированных интервью (кандидат/интервьюер). */
    public static final String BTN_SCHEDULE = "Расписание";
    public static final String BTN_HELP = "Справка";

    private ChatMenuKeyboardBuilder() {
    }

    /** Проверка: текст совпадает с одной из кнопок меню (для выхода из любого flow). */
    public static boolean isMenuButton(String text) {
        if (text == null || text.isBlank()) return false;
        String t = text.strip();
        return BTN_CREATE_INTERVIEW.equals(t) || BTN_FIND_PARTNER.equals(t)
                || BTN_SCHEDULE.equals(t) || BTN_HELP.equals(t);
    }

    public static ReplyKeyboardMarkup buildPersistentKeyboard() {
        KeyboardRow row1 = new KeyboardRow();
        row1.add(BTN_CREATE_INTERVIEW);
        row1.add(BTN_FIND_PARTNER);

        KeyboardRow row2 = new KeyboardRow();
        row2.add(BTN_SCHEDULE);
        row2.add(BTN_HELP);

        return ReplyKeyboardMarkup.builder()
                .keyboard(List.of(row1, row2))
                .resizeKeyboard(true)
                .oneTimeKeyboard(false)
                .selective(false)
                .build();
    }
}
