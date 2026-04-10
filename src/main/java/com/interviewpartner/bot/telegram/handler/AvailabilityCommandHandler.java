package com.interviewpartner.bot.telegram.handler;

import com.interviewpartner.bot.model.Language;
import com.interviewpartner.bot.model.Schedule;
import com.interviewpartner.bot.model.User;
import com.interviewpartner.bot.service.ScheduleService;
import com.interviewpartner.bot.service.UserService;
import com.interviewpartner.bot.telegram.flow.ConversationStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;

@Order(22)
@Component
@RequiredArgsConstructor
public class AvailabilityCommandHandler implements BotCommandHandler {

    private final UserService userService;
    private final ScheduleService scheduleService;
    private final ConversationStateService stateService;

    @Override
    public boolean canHandle(Update update) {
        return false;
    }

    @Override
    public void handle(Update update, TelegramClient telegramClient) {
        var message = update.getMessage();
        Long chatId = message.getChatId();
        var from = message.getFrom();
        if (from == null) return;

        String username = from.getUserName() != null ? from.getUserName() : from.getFirstName();
        User user = userService.registerUser(from.getId(), username != null ? username : "user");
        var state = stateService.startSchedule(chatId, user.getId());

        try {
            List<Schedule> slots = scheduleService.getUserSchedule(user.getId());
            if (slots.isEmpty()) {
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text("Окна доступности для взаимных часовых слотов: укажите направление.")
                        .replyMarkup(languageKeyboard())
                        .build());
            } else {
                Language language = slots.get(0).getLanguage();
                state.language = language;
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text(renderSchedule(slots, language))
                        .replyMarkup(scheduleMenuKeyboard())
                        .build());
            }
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        }
    }

    public static String renderSchedule(List<Schedule> slots, Language language) {
        StringBuilder sb = new StringBuilder("Мои окна доступности (взаимные слоты)");
        if (language != null) sb.append(" (").append(language).append(")");
        sb.append(":\n\n");
        if (slots.isEmpty()) {
            sb.append("Слотов нет. Добавьте окна — так проще подобрать время с другими участниками.");
        } else {
            for (Schedule s : slots) {
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

    public static InlineKeyboardMarkup scheduleMenuKeyboard() {
        return InlineKeyboardMarkup.builder().keyboard(List.of(
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("➕ Добавить слот").callbackData("sc:add").build()),
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("🗑 Удалить слот").callbackData("sc:remove").build()),
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("Закрыть").callbackData("sc:close").build())
        )).build();
    }

    public static InlineKeyboardMarkup languageKeyboard() {
        return InlineKeyboardMarkup.builder().keyboard(List.of(
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("Java").callbackData("sc:lang:JAVA").build(),
                        InlineKeyboardButton.builder().text("C#").callbackData("sc:lang:CSHARP").build()),
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("Python").callbackData("sc:lang:PYTHON").build(),
                        InlineKeyboardButton.builder().text("JavaScript").callbackData("sc:lang:JAVASCRIPT").build(),
                        InlineKeyboardButton.builder().text("Kotlin").callbackData("sc:lang:KOTLIN").build()),
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("Algorithms").callbackData("sc:lang:ALGORITHMS").build(),
                        InlineKeyboardButton.builder().text("Product Manager").callbackData("sc:lang:PRODUCT_MANAGER").build()
                       ),
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("Swift").callbackData("sc:lang:SWIFT").build(),
                        InlineKeyboardButton.builder().text("Go").callbackData("sc:lang:GO").build()),
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("QA").callbackData("sc:lang:QA").build(),
                        InlineKeyboardButton.builder().text("Data Analytics").callbackData("sc:lang:DATA_ANALYTICS").build()),
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("Business Analysis").callbackData("sc:lang:BUSINESS_ANALYSIS").build(),
                        InlineKeyboardButton.builder().text("System Analysis").callbackData("sc:lang:SYSTEM_ANALYSIS").build())
        )).build();
    }
}
