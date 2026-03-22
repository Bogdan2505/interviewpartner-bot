package com.interviewpartner.bot.telegram.handler;

import com.interviewpartner.bot.model.Interview;
import com.interviewpartner.bot.model.InterviewStatus;
import com.interviewpartner.bot.model.User;
import com.interviewpartner.bot.service.InterviewService;
import com.interviewpartner.bot.service.UserService;
import com.interviewpartner.bot.telegram.flow.ConversationStateService;
import com.interviewpartner.bot.telegram.flow.InterviewCalendarState;
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

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Order(23)
@Component
@RequiredArgsConstructor
public class ScheduleCommandHandler implements BotCommandHandler {

    private static final String CMD = "/schedule";
    private static final String IC_PREFIX = "ic:";
    private static final String IC_NOOP = IC_PREFIX + "noop";

    private static final String ROLE_CANDIDATE = "🧑‍💻";
    private static final String ROLE_INTERVIEWER = "🧑‍🏫";

    private final UserService userService;
    private final InterviewService interviewService;
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
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text("Ошибка: не удалось определить пользователя.")
                        .build());
                return;
            }

            String username = from.getUserName() != null ? from.getUserName() : from.getFirstName();
            User user = userService.registerUser(from.getId(), username != null ? username : "user");

            InterviewCalendarState state = stateService.startInterviewCalendar(chatId, user.getId());
            Long userId = user.getId();

            // Выводим месяц календаря по текущим запланированным собеседованиям.
            List<Interview> scheduled = interviewService.getUserInterviews(userId, InterviewStatus.SCHEDULED);
            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(renderMonth(state.calendarYear, state.calendarMonth))
                    .replyMarkup(monthKeyboard(state.calendarYear, state.calendarMonth, userId, scheduled))
                    .build());
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        }
    }

    private static String renderMonth(int year, int month) {
        DateTimeFormatter df = DateTimeFormatter.ofPattern("dd.MM.yyyy");
        // просто подсказка; дата в тексте не кликается
        LocalDate first = YearMonth.of(year, month).atDay(1);
        return "Календарь собеседований на " + MONTH_NAMES_RU[month - 1] + " " + year + ".\n\n"
                + "Тыкайте на день с собеседованиями ("
                + df.format(first) + " и далее).";
    }

    private static InlineKeyboardMarkup monthKeyboard(int year, int month, Long userId, List<Interview> scheduled) {
        YearMonth ym = YearMonth.of(year, month);

        Map<LocalDate, EnumSet<RoleType>> rolesByDate = scheduled.stream()
                .map(i -> {
                    LocalDate d = i.getDateTime().toLocalDate();
                    return Map.entry(d, i);
                })
                .filter(e -> e.getKey().getYear() == year && e.getKey().getMonthValue() == month)
                .collect(Collectors.groupingBy(
                        Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())
                ))
                .entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> roleSetForUser(e.getValue(), userId)
                ));

        List<InlineKeyboardRow> rows = new java.util.ArrayList<>();
        String monthTitle = MONTH_NAMES_RU[month - 1] + " " + year;

        YearMonth prev = ym.minusMonths(1);
        YearMonth next = ym.plusMonths(1);
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder()
                        .text("◀")
                        .callbackData(IC_PREFIX + "calnav:" + prev.getYear() + "-" + prev.getMonthValue())
                        .build(),
                InlineKeyboardButton.builder().text(monthTitle).callbackData(IC_NOOP).build(),
                InlineKeyboardButton.builder()
                        .text("▶")
                        .callbackData(IC_PREFIX + "calnav:" + next.getYear() + "-" + next.getMonthValue())
                        .build()
        ));

        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("Пн").callbackData(IC_NOOP).build(),
                InlineKeyboardButton.builder().text("Вт").callbackData(IC_NOOP).build(),
                InlineKeyboardButton.builder().text("Ср").callbackData(IC_NOOP).build(),
                InlineKeyboardButton.builder().text("Чт").callbackData(IC_NOOP).build(),
                InlineKeyboardButton.builder().text("Пт").callbackData(IC_NOOP).build(),
                InlineKeyboardButton.builder().text("Сб").callbackData(IC_NOOP).build(),
                InlineKeyboardButton.builder().text("Вс").callbackData(IC_NOOP).build()
        ));

        int firstDay = ym.atDay(1).getDayOfWeek().getValue();
        int len = ym.lengthOfMonth();
        List<InlineKeyboardButton> week = new java.util.ArrayList<>();
        for (int i = 1; i < firstDay; i++) {
            week.add(InlineKeyboardButton.builder().text(" ").callbackData(IC_NOOP).build());
        }

        for (int day = 1; day <= len; day++) {
            LocalDate date = ym.atDay(day);
            EnumSet<RoleType> roles = rolesByDate.getOrDefault(date, EnumSet.noneOf(RoleType.class));
            String label = buildDayLabel(day, roles);
            week.add(InlineKeyboardButton.builder()
                    .text(label)
                    .callbackData(IC_PREFIX + "day:" + date)
                    .build());
            if (week.size() == 7) {
                rows.add(new InlineKeyboardRow(week));
                week = new java.util.ArrayList<>();
            }
        }

        if (!week.isEmpty()) {
            while (week.size() < 7) week.add(InlineKeyboardButton.builder().text(" ").callbackData(IC_NOOP).build());
            rows.add(new InlineKeyboardRow(week));
        }

        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("Закрыть").callbackData(IC_PREFIX + "close").build()
        ));

        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private enum RoleType {
        CANDIDATE,
        INTERVIEWER
    }

    private static EnumSet<RoleType> roleSetForUser(List<Interview> interviews, Long userId) {
        EnumSet<RoleType> out = EnumSet.noneOf(RoleType.class);
        for (Interview i : interviews) {
            if (i.getCandidate() != null && i.getCandidate().getId() != null && i.getCandidate().getId().equals(userId)) {
                out.add(RoleType.CANDIDATE);
            }
            if (i.getInterviewer() != null && i.getInterviewer().getId() != null && i.getInterviewer().getId().equals(userId)) {
                out.add(RoleType.INTERVIEWER);
            }
        }
        return out;
    }

    private static String buildDayLabel(int day, EnumSet<RoleType> roles) {
        if (roles.isEmpty()) return String.valueOf(day);
        StringBuilder sb = new StringBuilder();
        sb.append(day);
        if (roles.contains(RoleType.CANDIDATE)) sb.append(" ").append(ROLE_CANDIDATE);
        if (roles.contains(RoleType.INTERVIEWER)) sb.append(" ").append(ROLE_INTERVIEWER);
        // ограничиваем длину для надёжности
        String s = sb.toString();
        return s.length() > 32 ? s.substring(0, 31) : s;
    }

    private static final String[] MONTH_NAMES_RU = new String[]{"Янв", "Фев", "Мар", "Апр", "Май", "Июн", "Июл", "Авг", "Сен", "Окт", "Ноя", "Дек"};
}
