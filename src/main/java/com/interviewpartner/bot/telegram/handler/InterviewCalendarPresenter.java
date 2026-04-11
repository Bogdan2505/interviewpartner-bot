package com.interviewpartner.bot.telegram.handler;

import com.interviewpartner.bot.model.Interview;
import com.interviewpartner.bot.model.InterviewRequest;
import com.interviewpartner.bot.model.InterviewRequestStatus;
import com.interviewpartner.bot.model.InterviewStatus;
import com.interviewpartner.bot.service.InterviewService;
import com.interviewpartner.bot.service.request.InterviewRequestService;
import com.interviewpartner.bot.telegram.flow.InterviewCalendarState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Отображение месячного календаря расписания (общий для /schedule и callback ic:*).
 */
@Component
@RequiredArgsConstructor
public class InterviewCalendarPresenter {

    private static final String[] MONTH_NAMES_RU = new String[]{
            "Янв", "Фев", "Мар", "Апр", "Май", "Июн", "Июл", "Авг", "Сен", "Окт", "Ноя", "Дек"
    };

    private final InterviewRequestService interviewRequestService;
    private final InterviewService interviewService;
    private final Clock clock;

    /**
     * @param alignMonthToFirstUpcoming при true (первое открытие /schedule) месяц ставится на ближайшую дату с актуальной заявкой,
     *                                  иначе слот в другом месяце не виден без ручного листания.
     */
    public void sendMonthView(Long chatId, Integer messageId, InterviewCalendarState state, TelegramClient telegramClient,
                              boolean alignMonthToFirstUpcoming)
            throws TelegramApiException {
        List<InterviewRequest> scheduled = interviewRequestService.getUserRequests(state.userId, null);
        List<Interview> interviews = interviewService.getUserInterviews(state.userId, InterviewStatus.SCHEDULED);
        if (alignMonthToFirstUpcoming) {
            alignStateMonthToFirstUpcoming(state, scheduled, interviews);
        }
        int year = state.calendarYear;
        int month = state.calendarMonth;
        InlineKeyboardMarkup keyboard = buildMonthKeyboard(year, month, scheduled, interviews);

        String text = "Расписание — " + MONTH_NAMES_RU[month - 1] + " " + year + ".\nВыберите день.";

        if (messageId != null) {
            try {
                telegramClient.execute(EditMessageText.builder()
                        .chatId(chatId)
                        .messageId(messageId)
                        .text(text)
                        .replyMarkup(keyboard)
                        .build());
            } catch (TelegramApiException e) {
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text(text)
                        .replyMarkup(keyboard)
                        .build());
            }
        } else {
            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .replyMarkup(keyboard)
                    .build());
        }
    }

    /** Сохраняет текущий месяц навигации (после {@code ic:calnav} и т.п.). */
    public void sendMonthView(Long chatId, Integer messageId, InterviewCalendarState state, TelegramClient telegramClient)
            throws TelegramApiException {
        sendMonthView(chatId, messageId, state, telegramClient, false);
    }

    private void alignStateMonthToFirstUpcoming(InterviewCalendarState state,
                                                List<InterviewRequest> scheduled,
                                                List<Interview> interviews) {
        LocalDate today = LocalDate.now(clock);
        LocalDate minReq = scheduled.stream()
                .filter(i -> i.getDateTime() != null)
                .filter(i -> i.getStatus() != InterviewRequestStatus.DECLINED
                        && i.getStatus() != InterviewRequestStatus.CANCELLED)
                .map(i -> i.getDateTime().toLocalDate())
                .filter(d -> !d.isBefore(today))
                .min(LocalDate::compareTo)
                .orElse(null);
        LocalDate minIv = interviews.stream()
                .filter(i -> i.getDateTime() != null)
                .map(i -> i.getDateTime().toLocalDate())
                .filter(d -> !d.isBefore(today))
                .min(LocalDate::compareTo)
                .orElse(null);
        LocalDate minUpcoming = minReq == null ? minIv : (minIv == null ? minReq : minReq.isBefore(minIv) ? minReq : minIv);
        if (minUpcoming != null) {
            state.calendarYear = minUpcoming.getYear();
            state.calendarMonth = minUpcoming.getMonthValue();
        } else {
            LocalDate now = LocalDate.now(clock);
            state.calendarYear = now.getYear();
            state.calendarMonth = now.getMonthValue();
        }
    }

    public InlineKeyboardMarkup buildMonthKeyboard(int year, int month, List<InterviewRequest> scheduled, List<Interview> interviews) {
        YearMonth ym = YearMonth.of(year, month);

        LocalDate today = LocalDate.now(clock);
        Set<LocalDate> datesWithSlots = new HashSet<>();
        for (InterviewRequest i : scheduled) {
            if (i.getDateTime() == null) continue;
            if (i.getStatus() == InterviewRequestStatus.DECLINED
                    || i.getStatus() == InterviewRequestStatus.CANCELLED) {
                continue;
            }
            LocalDate d = i.getDateTime().toLocalDate();
            if (d.isBefore(today)) {
                continue;
            }
            if (d.getYear() != year || d.getMonthValue() != month) continue;
            datesWithSlots.add(d);
        }
        for (Interview iv : interviews) {
            if (iv.getDateTime() == null) continue;
            LocalDate d = iv.getDateTime().toLocalDate();
            if (d.isBefore(today)) {
                continue;
            }
            if (d.getYear() != year || d.getMonthValue() != month) continue;
            datesWithSlots.add(d);
        }

        List<InlineKeyboardRow> rows = new ArrayList<>();
        String monthTitle = MONTH_NAMES_RU[month - 1] + " " + year;

        YearMonth prev = ym.minusMonths(1);
        YearMonth next = ym.plusMonths(1);
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("◀").callbackData("ic:calnav:" + prev.getYear() + "-" + prev.getMonthValue()).build(),
                InlineKeyboardButton.builder().text(monthTitle).callbackData("ic:noop").build(),
                InlineKeyboardButton.builder().text("▶").callbackData("ic:calnav:" + next.getYear() + "-" + next.getMonthValue()).build()
        ));

        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("Пн").callbackData("ic:noop").build(),
                InlineKeyboardButton.builder().text("Вт").callbackData("ic:noop").build(),
                InlineKeyboardButton.builder().text("Ср").callbackData("ic:noop").build(),
                InlineKeyboardButton.builder().text("Чт").callbackData("ic:noop").build(),
                InlineKeyboardButton.builder().text("Пт").callbackData("ic:noop").build(),
                InlineKeyboardButton.builder().text("Сб").callbackData("ic:noop").build(),
                InlineKeyboardButton.builder().text("Вс").callbackData("ic:noop").build()
        ));

        int firstDay = ym.atDay(1).getDayOfWeek().getValue();
        int len = ym.lengthOfMonth();
        List<InlineKeyboardButton> week = new ArrayList<>();
        for (int i = 1; i < firstDay; i++) {
            week.add(InlineKeyboardButton.builder().text(" ").callbackData("ic:noop").build());
        }

        for (int day = 1; day <= len; day++) {
            LocalDate date = ym.atDay(day);
            boolean pastDay = date.isBefore(today);
            boolean hasSlots = datesWithSlots.contains(date);
            String label;
            String dayCallback;
            if (pastDay) {
                label = String.valueOf(day);
                dayCallback = "ic:daypast";
            } else if (hasSlots) {
                label = day + TelegramScheduleUi.DAY_HAS_ACTIVITY;
                dayCallback = "ic:day:" + date;
            } else {
                label = String.valueOf(day);
                dayCallback = "ic:day:" + date;
            }
            week.add(InlineKeyboardButton.builder().text(label).callbackData(dayCallback).build());
            if (week.size() == 7) {
                rows.add(new InlineKeyboardRow(week));
                week = new ArrayList<>();
            }
        }

        if (!week.isEmpty()) {
            while (week.size() < 7) {
                week.add(InlineKeyboardButton.builder().text(" ").callbackData("ic:noop").build());
            }
            rows.add(new InlineKeyboardRow(week));
        }

        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("Закрыть").callbackData("ic:close").build()
        ));

        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }
}
