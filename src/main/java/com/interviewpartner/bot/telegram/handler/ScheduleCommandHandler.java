package com.interviewpartner.bot.telegram.handler;

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

@Order(23)
@Component
@RequiredArgsConstructor
public class ScheduleCommandHandler implements BotCommandHandler {

    private static final String CMD = "/schedule";

    private final UserService userService;
    private final ScheduleService scheduleService;
    private final ConversationStateService stateService;

    @Override
    public boolean canHandle(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return false;
        }
        String text = update.getMessage().getText().strip();
        return text.startsWith(CMD) || text.equalsIgnoreCase(ChatMenuKeyboardBuilder.BTN_SCHEDULE);
    }

    @Override
    public void handle(Update update, TelegramClient telegramClient) {
        try {
            var message = update.getMessage();
            Long chatId = message.getChatId();
            var from = message.getFrom();
            if (from == null) {
                telegramClient.execute(SendMessage.builder().chatId(chatId).text("Ошибка: не удалось определить пользователя.").build());
                return;
            }
            String username = from.getUserName() != null ? from.getUserName() : from.getFirstName();
            User user = userService.registerUser(from.getId(), username != null ? username : "user");
            stateService.startSchedule(chatId, user.getId());

            List<Schedule> schedule = scheduleService.getUserSchedule(user.getId());
            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(render(schedule))
                    .replyMarkup(menuKeyboard())
                    .build());
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        }
    }

    private static InlineKeyboardMarkup menuKeyboard() {
        var add = InlineKeyboardButton.builder().text("Добавить слот").callbackData("sc:add").build();
        var remove = InlineKeyboardButton.builder().text("Удалить слот").callbackData("sc:remove").build();
        var cancel = InlineKeyboardButton.builder().text("Закрыть").callbackData("sc:close").build();
        List<InlineKeyboardRow> rows = List.of(
                new InlineKeyboardRow(add),
                new InlineKeyboardRow(remove),
                new InlineKeyboardRow(cancel)
        );
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private static String render(List<Schedule> schedule) {
        if (schedule.isEmpty()) {
            return "Ваше расписание, когда вы готовы провести собеседование, пока пустое.\n\nДобавьте доступность, чтобы вас могли находить партнёры.";
        }
        StringBuilder sb = new StringBuilder("Ваше расписание, когда вы готовы провести собеседование:\n");
        for (Schedule s : schedule) {
            sb.append("- ").append(s.getDayOfWeek())
                    .append(" ").append(s.getStartTime())
                    .append("–").append(s.getEndTime())
                    .append(" (id=").append(s.getId()).append(")")
                    .append("\n");
        }
        return sb.toString();
    }
}
