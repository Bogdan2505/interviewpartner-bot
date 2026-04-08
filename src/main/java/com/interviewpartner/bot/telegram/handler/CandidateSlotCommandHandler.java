package com.interviewpartner.bot.telegram.handler;

import com.interviewpartner.bot.model.CandidateSlot;
import com.interviewpartner.bot.model.Language;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.util.List;

/**
 * Вспомогательные методы для отображения слотов кандидата.
 * Управляет таблицей candidate_slots.
 */
public final class CandidateSlotCommandHandler {

    private CandidateSlotCommandHandler() {
    }

    public static String renderSlots(List<CandidateSlot> slots, Language language) {
        StringBuilder sb = new StringBuilder("Мои предпочтения по времени (для подбора взаимных слотов)");
        if (language != null) sb.append(" (").append(language).append(")");
        sb.append(":\n\n");
        if (slots.isEmpty()) {
            sb.append("Слотов нет. Добавьте удобные часы — так проще согласовать встречу.");
        } else {
            for (CandidateSlot s : slots) {
                sb.append("• ").append(dayRu(s.getDayOfWeek()))
                        .append(" ").append(s.getStartTime()).append("–").append(s.getEndTime())
                        .append("\n");
            }
        }
        return sb.toString();
    }

    private static String dayRu(java.time.DayOfWeek d) {
        return switch (d) {
            case MONDAY -> "Пн";
            case TUESDAY -> "Вт";
            case WEDNESDAY -> "Ср";
            case THURSDAY -> "Чт";
            case FRIDAY -> "Пт";
            case SATURDAY -> "Сб";
            case SUNDAY -> "Вс";
        };
    }

    public static InlineKeyboardMarkup slotMenuKeyboard() {
        return InlineKeyboardMarkup.builder().keyboard(List.of(
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("➕ Добавить слот").callbackData("cs:add").build()),
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("🗑 Удалить слот").callbackData("cs:remove").build()),
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("Закрыть").callbackData("cs:close").build())
        )).build();
    }

    public static InlineKeyboardMarkup languageKeyboard() {
        return InlineKeyboardMarkup.builder().keyboard(List.of(
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("Java").callbackData("cs:lang:JAVA").build(),
                        InlineKeyboardButton.builder().text("C#").callbackData("cs:lang:CSHARP").build()),
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("Python").callbackData("cs:lang:PYTHON").build(),
                        InlineKeyboardButton.builder().text("Product Manager").callbackData("cs:lang:PRODUCT_MANAGER").build(),
                        InlineKeyboardButton.builder().text("JavaScript").callbackData("cs:lang:JAVASCRIPT").build(),
                        InlineKeyboardButton.builder().text("Kotlin").callbackData("cs:lang:KOTLIN").build()),
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("Swift").callbackData("cs:lang:SWIFT").build(),
                        InlineKeyboardButton.builder().text("Go").callbackData("cs:lang:GO").build()),
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("QA").callbackData("cs:lang:QA").build(),
                        InlineKeyboardButton.builder().text("Data Analytics").callbackData("cs:lang:DATA_ANALYTICS").build()),
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("Business Analysis").callbackData("cs:lang:BUSINESS_ANALYSIS").build(),
                        InlineKeyboardButton.builder().text("System Analysis").callbackData("cs:lang:SYSTEM_ANALYSIS").build())
        )).build();
    }
}
