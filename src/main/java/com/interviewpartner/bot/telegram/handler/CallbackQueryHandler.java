package com.interviewpartner.bot.telegram.handler;

import com.interviewpartner.bot.exception.InterviewConflictException;
import com.interviewpartner.bot.exception.InterviewRequestForbiddenException;
import com.interviewpartner.bot.exception.UserNotFoundException;
import com.interviewpartner.bot.model.Interview;
import com.interviewpartner.bot.model.InterviewFormat;
import com.interviewpartner.bot.model.InterviewRequest;
import com.interviewpartner.bot.model.InterviewRequestStatus;
import com.interviewpartner.bot.model.InterviewStatus;
import com.interviewpartner.bot.model.Language;
import com.interviewpartner.bot.model.Level;
import com.interviewpartner.bot.model.User;
import com.interviewpartner.bot.service.InterviewService;
import com.interviewpartner.bot.service.UserService;
import com.interviewpartner.bot.service.dto.AvailableSlotDto;
import com.interviewpartner.bot.telegram.flow.ConversationStateService;
import com.interviewpartner.bot.telegram.flow.CreateInterviewState;
import com.interviewpartner.bot.telegram.flow.InterviewCalendarState;
import com.interviewpartner.bot.service.request.InterviewRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Обрабатывает нажатия inline-кнопок (callback_data из главного меню и др.).
 */
@Order(15)
@Component
@RequiredArgsConstructor
public class CallbackQueryHandler implements BotCommandHandler {

    private static final DateTimeFormatter DT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * Сообщение пользователю по {@link IllegalArgumentException} из создания заявки/собеседования
     * (раньше любая такая ошибка при «Создать» показывалась как «время в прошлом»).
     */
    private static String userMessageForInterviewOrRequestCreateFailure(IllegalArgumentException e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (m.contains("must not be in the past")) {
            return "Нельзя записаться на время в прошлом. Выберите другое время.";
        }
        if (m.contains("Duplicate pending")) {
            return "Такая открытая заявка на это время уже есть. Посмотрите «Расписание» или выберите другое время.";
        }
        if (m.contains("conflicting interview")) {
            return "В это время у вас или партнёра уже есть собеседование. Выберите другое время.";
        }
        if (m.contains("solo slots") || m.contains("owner ids must match")) {
            return "Прямая заявка к другому пользователю недоступна. Запишитесь через «Доступные слоты» или создайте открытый слот только на себя.";
        }
        return "Не удалось создать заявку. Выберите другое время или начните заново: /create_interview";
    }

    private final ConversationStateService stateService;
    private final InterviewService interviewService;
    private final UserService userService;
    private final InterviewRequestService interviewRequestService;
    private final InterviewCalendarPresenter interviewCalendarPresenter;
    private final Clock clock;

    @Override
    public boolean canHandle(Update update) {
        return update.hasCallbackQuery();
    }

    @Override
    public void handle(Update update, TelegramClient telegramClient) {
        var callback = update.getCallbackQuery();
        String data = callback.getData();
        Long chatId = callback.getMessage().getChatId();

        String safeData = data != null ? data : "";

        try {
            if ("ci:slotpast".equals(safeData)) {
                telegramClient.execute(AnswerCallbackQuery.builder()
                        .callbackQueryId(callback.getId())
                        .text("Прошедшие даты недоступны. Выберите сегодня или позже.")
                        .build());
                return;
            }
            if ("ci:timepast".equals(safeData)) {
                telegramClient.execute(AnswerCallbackQuery.builder()
                        .callbackQueryId(callback.getId())
                        .text("Это время уже прошло. Выберите другое.")
                        .build());
                return;
            }
            if ("ic:daypast".equals(safeData)) {
                telegramClient.execute(AnswerCallbackQuery.builder()
                        .callbackQueryId(callback.getId())
                        .text("Прошедшие даты недоступны. Выберите сегодня или позже.")
                        .build());
                return;
            }
            if ("ic:timepast".equals(safeData)) {
                telegramClient.execute(AnswerCallbackQuery.builder()
                        .callbackQueryId(callback.getId())
                        .text("Это время уже прошло. Выберите другое.")
                        .build());
                return;
            }
            telegramClient.execute(AnswerCallbackQuery.builder()
                    .callbackQueryId(callback.getId())
                    .build());
            if (safeData.startsWith("ci:")) {
                handleCreateInterviewCallback(chatId, safeData, telegramClient);
                return;
            }
            if (safeData.startsWith("as:")) {
                handleAvailableSlotsCallback(chatId, safeData, telegramClient);
                return;
            }
            if (safeData.startsWith("ic:")) {
                Integer messageId = callback.getMessage() != null ? callback.getMessage().getMessageId() : null;
                handleInterviewCalendarCallback(chatId, messageId, safeData, callback.getFrom(), telegramClient);
                return;
            }
            if (safeData.startsWith("sc:")) {
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text("Раздел расписаний отключен и удалён из актуального флоу.")
                        .replyMarkup(ChatMenuKeyboardBuilder.buildPersistentKeyboard())
                        .build());
                return;
            }
            if (safeData.startsWith("cs:")) {
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text("Раздел candidate slots отключен и удалён из актуального флоу.")
                        .replyMarkup(ChatMenuKeyboardBuilder.buildPersistentKeyboard())
                        .build());
                return;
            }
            if (safeData.startsWith("ir:")) {
                handleInterviewRequestCallback(chatId, telegramClient);
                return;
            }
            if (safeData.startsWith("cmd:")) {
                handleMainMenuCallback(chatId, safeData, callback.getFrom(), telegramClient);
                return;
            }
            telegramClient.execute(SendMessage.builder().chatId(chatId).text("Действие в разработке.").replyMarkup(ChatMenuKeyboardBuilder.buildPersistentKeyboard()).build());
        } catch (TelegramApiException e) {
            throw new RuntimeException("Failed to handle callback", e);
        }
    }

    private void handleCreateInterviewCallback(Long chatId, String data, TelegramClient telegramClient) throws TelegramApiException {
        if (data.equals("ci:noop")) return;
        if (data.equals("ci:cancel")) {
            stateService.clearCreateInterview(chatId);
            telegramClient.execute(SendMessage.builder().chatId(chatId).text("Ок, отменил создание собеседования.").build());
            sendMainMenu(chatId, telegramClient);
            return;
        }
        CreateInterviewState state = stateService.getCreateInterview(chatId).orElse(null);
        if (state == null) {
            telegramClient.execute(SendMessage.builder().chatId(chatId).text("Сессия истекла. Начните заново: /create_interview").replyMarkup(ChatMenuKeyboardBuilder.buildPersistentKeyboard()).build());
            return;
        }

        // Состояние в памяти: повтор «Записаться» сбрасывает сессию, а старые inline-кнопки всё ещё шлют ci:* без языка → в тексте (null) и падение на confirm.
        // Две машины на Fly без sticky session дают тот же эффект.
        if (!allowsCreateInterviewCallbackWithoutLanguage(data)
                && (state.language == null || state.format == null)) {
            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("Кнопка из старого сообщения или сессия на другом сервере. Нажмите «Записаться на собеседование» и пройдите шаги заново.")
                    .replyMarkup(ChatMenuKeyboardBuilder.buildPersistentKeyboard())
                    .build());
            stateService.clearCreateInterview(chatId);
            return;
        }

        if (data.startsWith("ci:lang:")) {
            state.language = Language.valueOf(data.substring("ci:lang:".length()));
            state.format = InterviewFormat.TECHNICAL;
            state.step = CreateInterviewState.Step.LEVEL;
            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("Выберите ваш грейд:")
                    .replyMarkup(createInterviewLevelKeyboard())
                    .build());
            return;
        }

        if (data.startsWith("ci:level:")) {
            String levelStr = data.substring("ci:level:".length());
            state.level = "ANY".equals(levelStr) ? null : Level.valueOf(levelStr);
            List<AvailableSlotDto> slots = interviewService.getAvailableSlotsAsCandidate(
                    state.candidateUserId, state.language, state.level, 14);
            if (slots == null) slots = List.of();
            state.availableSlots = slots;
            state.step = CreateInterviewState.Step.VIEW_SLOT_DATES;
            LocalDate today = LocalDate.now(clock);
            LocalDate firstSlotDate = slots.stream()
                    .map(s -> s.dateTime().toLocalDate())
                    .min(LocalDate::compareTo)
                    .map(d -> d.isBefore(today) ? today : d)
                    .orElse(today);
            state.slotCalendarYear = firstSlotDate.getYear();
            state.slotCalendarMonth = firstSlotDate.getMonthValue();
            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("Выберите день в календаре (" + TelegramScheduleUi.DAY_HAS_ACTIVITY + " — есть слоты). Затем выберите время.")
                    .replyMarkup(slotCalendarKeyboard(slots, state.slotCalendarYear, state.slotCalendarMonth, clock))
                    .build());
            return;
        }

        if (data.startsWith("ci:slotcalnav:")) {
            String ym = data.substring("ci:slotcalnav:".length());
            String[] parts = ym.split("-");
            if (parts.length != 2) return;
            state.slotCalendarYear = Integer.parseInt(parts[0]);
            state.slotCalendarMonth = Integer.parseInt(parts[1]);
            List<AvailableSlotDto> slots = state.availableSlots != null ? state.availableSlots : List.of();
            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("Выберите день:")
                    .replyMarkup(slotCalendarKeyboard(slots, state.slotCalendarYear, state.slotCalendarMonth, clock))
                    .build());
            return;
        }
        if (data.startsWith("ci:slotdate:")) {
            String dateStr = data.substring("ci:slotdate:".length());
            LocalDate picked = LocalDate.parse(dateStr);
            if (picked.isBefore(LocalDate.now(clock))) {
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text("Нельзя выбрать дату в прошлом. Выберите сегодня или позже.")
                        .replyMarkup(slotCalendarKeyboard(state.availableSlots != null ? state.availableSlots : List.of(), state.slotCalendarYear, state.slotCalendarMonth, clock))
                        .build());
                return;
            }
            state.selectedSlotDate = picked;
            String dayLabel = state.selectedSlotDate.format(DATE_BTN) + " (" + DAY_NAMES_RU[state.selectedSlotDate.getDayOfWeek().getValue() - 1] + ")";

            Map<Integer, EnumSet<InterviewRequestStatus>> busyStatuses = buildBusyStatusesForDate(state.candidateUserId, state.selectedSlotDate);

            // Часы, в которые есть доступный партнёр
            Set<Integer> availableHours = (state.availableSlots == null ? List.<AvailableSlotDto>of() : state.availableSlots)
                    .stream()
                    .filter(s -> s.dateTime().toLocalDate().equals(state.selectedSlotDate))
                    .map(s -> s.dateTime().getHour())
                    .collect(java.util.stream.Collectors.toSet());

            state.step = CreateInterviewState.Step.VIEW_SLOTS;

            StringBuilder legend = new StringBuilder();
            if (!availableHours.isEmpty()) legend.append(TelegramScheduleUi.legendOpenPartnerSlot());
            if (!busyStatuses.isEmpty()) legend.append(TelegramScheduleUi.legendRequestAndConfirmed());

            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(dayLabel + (legend.length() > 0 ? legend.toString() : ""))
                    .replyMarkup(createInterviewTimePickerKeyboard(state.selectedSlotDate, busyStatuses, availableHours, LocalDateTime.now(clock)))
                    .build());
            return;
        }
        if (data.startsWith("ci:time:")) {
            String payload = data.substring("ci:time:".length());
            String[] parts = payload.split(":", 2);
            if (parts.length != 2) return;
            LocalDate date;
            int hour;
            try {
                date = LocalDate.parse(parts[0]);
                hour = Integer.parseInt(parts[1]);
            } catch (Exception e) {
                return;
            }
            if (hour < 0 || hour > 23) return;
            LocalDateTime chosen = date.atTime(hour, 0);
            if (chosen.isBefore(LocalDateTime.now(clock))) {
                Map<Integer, EnumSet<InterviewRequestStatus>> busyStatuses = buildBusyStatusesForDate(state.candidateUserId, date);
                Set<Integer> availableHours = (state.availableSlots == null ? List.<AvailableSlotDto>of() : state.availableSlots)
                        .stream()
                        .filter(s -> s.dateTime().toLocalDate().equals(date))
                        .map(s -> s.dateTime().getHour())
                        .collect(java.util.stream.Collectors.toSet());
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text("Это время уже прошло. Выберите другое.")
                        .replyMarkup(createInterviewTimePickerKeyboard(date, busyStatuses, availableHours, LocalDateTime.now(clock)))
                        .build());
                return;
            }
            state.selectedSlotDate = date;
            state.dateTime = chosen;
            state.durationMinutes = 60;

            // Автоматически назначаем партнёра из доступных слотов
            AvailableSlotDto matchedSlot = state.availableSlots == null ? null :
                    state.availableSlots.stream()
                            .filter(s -> s.dateTime().equals(state.dateTime))
                            .findFirst().orElse(null);

            if (matchedSlot != null) {
                state.openSlotRequestId = matchedSlot.openSlotRequestId();
                state.interviewerUserId = matchedSlot.partnerUserId();
                state.step = CreateInterviewState.Step.CONFIRM;
                String partnerLabel = matchedSlot.partnerLabel()
                        + (matchedSlot.partnerLevel() != null ? " [" + levelLabel(matchedSlot.partnerLevel()) + "]" : "");
                boolean joining = state.openSlotRequestId != null;
                String summary = (joining ? "Записаться на взаимный час?\n" : "Подтвердить создание открытого слота?\n")
                        + "Язык: " + state.language + "\n"
                        + (state.level != null ? "Грейд: " + levelLabel(state.level) + "\n" : "")
                        + "Дата/время: " + DT_FORMAT.format(state.dateTime) + "\n"
                        + "Длительность: " + state.durationMinutes + " мин\n"
                        + "Партнёр: " + partnerLabel;
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text(summary)
                        .replyMarkup(confirmKeyboard(joining))
                        .build());
            } else {
                // Нет готового слота — открытый часовой слот (после записи партнёра станет согласованным собеседованием)
                state.interviewerUserId = state.candidateUserId;
                state.step = CreateInterviewState.Step.CONFIRM;
                String summary = "Пока никто не открыл это время. Создать открытый часовой слот на "
                        + DT_FORMAT.format(state.dateTime) + " (" + state.language + ")"
                        + (state.level != null ? " [" + levelLabel(state.level) + "]" : "") + "?\n"
                        + "Когда партнёр запишется, в календаре это отобразится как согласованное собеседование (" + TelegramScheduleUi.CONFIRMED_ICON + ").";
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text(summary)
                        .replyMarkup(confirmKeyboard())
                        .build());
            }
            return;
        }

        if (data.startsWith("ci:slot:")) {
            String payload = data.substring("ci:slot:".length());
            if ("manual".equals(payload)) {
                state.step = CreateInterviewState.Step.VIEW_SLOT_DATES;
                LocalDate nowDate = LocalDate.now(clock);
                state.slotCalendarYear = nowDate.getYear();
                state.slotCalendarMonth = nowDate.getMonthValue();
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text("Выберите день в календаре, затем время:")
                        .replyMarkup(slotCalendarKeyboard(state.availableSlots != null ? state.availableSlots : List.of(), state.slotCalendarYear, state.slotCalendarMonth, clock))
                        .build());
                return;
            }
            if ("back".equals(payload)) {
                state.step = CreateInterviewState.Step.VIEW_SLOT_DATES;
                List<AvailableSlotDto> slots = state.availableSlots != null ? state.availableSlots : List.of();
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text("Выберите день в календаре:")
                        .replyMarkup(slotCalendarKeyboard(slots, state.slotCalendarYear, state.slotCalendarMonth, clock))
                        .build());
                return;
            }
            int index;
            try {
                index = Integer.parseInt(payload);
            } catch (NumberFormatException e) {
                return;
            }
            if (state.availableSlots == null || index < 0 || index >= state.availableSlots.size()) return;
            AvailableSlotDto slot = state.availableSlots.get(index);
            if (slot.dateTime().isBefore(LocalDateTime.now(clock))) {
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text("Этот слот уже в прошлом. Выберите другое время.")
                        .replyMarkup(slotCalendarKeyboard(state.availableSlots, state.slotCalendarYear, state.slotCalendarMonth, clock))
                        .build());
                return;
            }
            state.dateTime = slot.dateTime();
            state.durationMinutes = 60;
            state.openSlotRequestId = slot.openSlotRequestId();
            state.interviewerUserId = slot.partnerUserId();
            state.step = CreateInterviewState.Step.CONFIRM;
            String slotPartnerLabel = slot.partnerLabel()
                    + (slot.partnerLevel() != null ? " [" + levelLabel(slot.partnerLevel()) + "]" : "");
            String summary = "Подтвердить создание?\nЯзык: " + state.language
                    + (state.level != null ? "\nГрейд: " + levelLabel(state.level) : "")
                    + "\nДата/время: " + DT_FORMAT.format(state.dateTime) + "\nДлительность: " + state.durationMinutes + " мин.\nПартнёр: " + slotPartnerLabel;
            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(summary)
                    .replyMarkup(confirmKeyboard())
                    .build());
            return;
        }

        if (data.startsWith("ci:partner:")) {
            String payload = data.substring("ci:partner:".length());
            if (payload.equals("self")) {
                state.interviewerUserId = state.candidateUserId;
            } else {
                state.interviewerUserId = Long.parseLong(payload);
            }
            state.step = CreateInterviewState.Step.CONFIRM;
            String summary = "Подтвердить создание?\nЯзык: " + state.language + "\nФормат: " + state.format
                    + "\nДата/время: " + DT_FORMAT.format(state.dateTime) + "\nДлительность: " + state.durationMinutes + " мин.";
            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(summary)
                    .replyMarkup(confirmKeyboard())
                    .build());
            return;
        }

        if (data.startsWith("ci:confirm:")) {
            if (state.language == null || state.format == null || state.dateTime == null || state.durationMinutes == null
                    || state.candidateUserId == null || state.interviewerUserId == null) {
                telegramClient.execute(SendMessage.builder().chatId(chatId).text("Недостаточно данных для создания. Начните заново: /create_interview").build());
                stateService.clearCreateInterview(chatId);
                return;
            }
            String action = data.substring("ci:confirm:".length());
            if (action.equals("no")) {
                telegramClient.execute(SendMessage.builder().chatId(chatId).text("Ок, не создаю.").build());
                stateService.clearCreateInterview(chatId);
                sendMainMenu(chatId, telegramClient);
                return;
            }
            if (action.equals("yes")) {
                try {
                    if (state.openSlotRequestId != null) {
                        String levelInfo = state.level != null ? "\nГрейд: " + levelLabel(state.level) : "";
                        bookOpenSlotAndNotify(
                                chatId,
                                state.candidateUserId,
                                state.interviewerUserId,
                                state.language,
                                state.format,
                                state.dateTime,
                                state.durationMinutes,
                                levelInfo,
                                state.openSlotRequestId,
                                telegramClient);
                    } else {
                        boolean solo = state.candidateUserId.equals(state.interviewerUserId);
                        if (solo) {
                            interviewRequestService.createRequest(
                                    state.candidateUserId,
                                    state.interviewerUserId,
                                    state.language,
                                    state.format,
                                    state.dateTime,
                                    state.durationMinutes,
                                    state.level);
                            telegramClient.execute(SendMessage.builder().chatId(chatId)
                                    .text("Открытая заявка создана. Когда кто-то запишется на слот, вы получите уведомление, собеседование создастся автоматически.")
                                    .build());
                        } else {
                            telegramClient.execute(SendMessage.builder().chatId(chatId)
                                    .text("Прямая заявка к выбранному партнёру недоступна. Запишитесь через «Доступные слоты» в меню или создайте открытый слот на себя.")
                                    .build());
                        }
                    }
                    stateService.clearCreateInterview(chatId);
                    sendMainMenu(chatId, telegramClient);
                } catch (InterviewConflictException e) {
                    telegramClient.execute(SendMessage.builder().chatId(chatId)
                            .text("В это время у вас или партнёра уже есть собеседование. Выберите другое время.").build());
                    state.step = CreateInterviewState.Step.DATE_TIME;
                    telegramClient.execute(SendMessage.builder()
                            .chatId(chatId)
                            .text("Введите новую дату и время в формате: yyyy-MM-dd HH:mm (например 2026-03-25 19:00)")
                            .build());
                } catch (IllegalArgumentException e) {
                    telegramClient.execute(SendMessage.builder().chatId(chatId)
                            .text(userMessageForInterviewOrRequestCreateFailure(e))
                            .build());
                    state.step = CreateInterviewState.Step.VIEW_SLOT_DATES;
                    List<AvailableSlotDto> slots = state.availableSlots != null ? state.availableSlots : List.of();
                    telegramClient.execute(SendMessage.builder()
                            .chatId(chatId)
                            .text("Выберите день в календаре:")
                            .replyMarkup(slotCalendarKeyboard(slots, state.slotCalendarYear, state.slotCalendarMonth, clock))
                            .build());
                } catch (UserNotFoundException e) {
                    telegramClient.execute(SendMessage.builder().chatId(chatId).text("Ошибка: пользователь не найден. Начните заново: /create_interview").build());
                    stateService.clearCreateInterview(chatId);
                    sendMainMenu(chatId, telegramClient);
                }
            }
        }
    }

    private static String videoMeetingBlock(Interview interview) {
        String appendix = meetingLinkAppendix(interview);
        if (!appendix.isEmpty()) {
            return appendix;
        }
        if (!isPairedInterview(interview)) {
            return "";
        }
        return "\n\nСсылка на видеовстречу не сформирована. Обратитесь к администратору бота.";
    }

    private static boolean isPairedInterview(Interview interview) {
        if (interview == null) {
            return false;
        }
        var c = interview.getCandidate();
        var i = interview.getInterviewer();
        if (c == null || i == null || c.getId() == null || i.getId() == null) {
            return false;
        }
        return !c.getId().equals(i.getId());
    }

    private static String meetingLinkAppendix(Interview interview) {
        if (interview == null) {
            return "";
        }
        String url = interview.getVideoMeetingUrl();
        if (url == null || url.isBlank()) {
            return "";
        }
        String suffix = url.contains("meet.jit.si") ? " (Jitsi Meet, хорошо работает в Chrome и Yandex)" : "";
        return "\n\nСсылка на встречу" + suffix + ":\n" + url;
    }

    private static InlineKeyboardMarkup confirmKeyboard() {
        return confirmKeyboard(false);
    }

    private static InlineKeyboardMarkup confirmKeyboard(boolean joining) {
        var yes = InlineKeyboardButton.builder().text(joining ? "Записаться" : "Создать").callbackData("ci:confirm:yes").build();
        var no = InlineKeyboardButton.builder().text("Отмена").callbackData("ci:confirm:no").build();
        List<InlineKeyboardRow> rows = List.of(new InlineKeyboardRow(yes, no));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private static final DateTimeFormatter SLOT_LABEL = DateTimeFormatter.ofPattern("dd.MM HH:mm");
    private static final DateTimeFormatter DATE_BTN = DateTimeFormatter.ofPattern("dd.MM");

    private static final int QUICK_SLOTS_MAX = 12;

    private static InlineKeyboardMarkup slotCalendarKeyboard(List<AvailableSlotDto> slots, int year, int month, Clock clock) {
        if (slots == null) slots = List.of();
        Set<LocalDate> datesWithSlots = slots.stream().map(s -> s.dateTime().toLocalDate()).collect(Collectors.toSet());
        LocalDate today = LocalDate.now(clock);
        YearMonth ym = YearMonth.of(year, month);
        YearMonth minYm = YearMonth.from(today);
        List<InlineKeyboardRow> rows = new ArrayList<>();
        String monthTitle = MONTH_NAMES_RU[month - 1] + " " + year;
        YearMonth prev = ym.minusMonths(1);
        YearMonth next = ym.plusMonths(1);
        boolean canGoPrev = ym.isAfter(minYm);
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("◀").callbackData(canGoPrev ? "ci:slotcalnav:" + prev.getYear() + "-" + prev.getMonthValue() : "ci:slotpast").build(),
                InlineKeyboardButton.builder().text(monthTitle).callbackData("ci:noop").build(),
                InlineKeyboardButton.builder().text("▶").callbackData("ci:slotcalnav:" + next.getYear() + "-" + next.getMonthValue()).build()
        ));
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("Пн").callbackData("ci:noop").build(),
                InlineKeyboardButton.builder().text("Вт").callbackData("ci:noop").build(),
                InlineKeyboardButton.builder().text("Ср").callbackData("ci:noop").build(),
                InlineKeyboardButton.builder().text("Чт").callbackData("ci:noop").build(),
                InlineKeyboardButton.builder().text("Пт").callbackData("ci:noop").build(),
                InlineKeyboardButton.builder().text("Сб").callbackData("ci:noop").build(),
                InlineKeyboardButton.builder().text("Вс").callbackData("ci:noop").build()
        ));
        int firstDay = ym.atDay(1).getDayOfWeek().getValue();
        int len = ym.lengthOfMonth();
        List<InlineKeyboardButton> week = new ArrayList<>();
        for (int i = 1; i < firstDay; i++) {
            week.add(InlineKeyboardButton.builder().text(" ").callbackData("ci:noop").build());
        }
        for (int day = 1; day <= len; day++) {
            LocalDate date = ym.atDay(day);
            boolean pastDay = date.isBefore(today);
            boolean hasSlots = datesWithSlots.contains(date);
            String label;
            if (pastDay) {
                label = String.valueOf(day);
            } else if (hasSlots) {
                label = day + TelegramScheduleUi.DAY_HAS_ACTIVITY;
            } else {
                label = String.valueOf(day);
            }
            String dayCallback = pastDay ? "ci:slotpast" : "ci:slotdate:" + date;
            week.add(InlineKeyboardButton.builder().text(label).callbackData(dayCallback).build());
            if (week.size() == 7) {
                rows.add(new InlineKeyboardRow(week));
                week = new ArrayList<>();
            }
        }
        if (!week.isEmpty()) {
            while (week.size() < 7) week.add(InlineKeyboardButton.builder().text(" ").callbackData("ci:noop").build());
            rows.add(new InlineKeyboardRow(week));
        }
        // Быстрый выбор: кнопки слотов текущего месяца (до QUICK_SLOTS_MAX)
        List<InlineKeyboardButton> quickRow = new ArrayList<>();
        int quickCount = 0;
        for (int i = 0; i < slots.size() && quickCount < QUICK_SLOTS_MAX; i++) {
            LocalDate d = slots.get(i).dateTime().toLocalDate();
            if (d.getYear() != year || d.getMonthValue() != month) continue;
            AvailableSlotDto s = slots.get(i);
            String label = SLOT_LABEL.format(s.dateTime());
            if (label.length() > 32) label = label.substring(0, 29) + "...";
            quickRow.add(InlineKeyboardButton.builder().text(label).callbackData("ci:slot:" + i).build());
            quickCount++;
            if (quickRow.size() == 2) {
                rows.add(new InlineKeyboardRow(quickRow));
                quickRow = new ArrayList<>();
            }
        }
        if (!quickRow.isEmpty()) rows.add(new InlineKeyboardRow(quickRow));
        rows.add(new InlineKeyboardRow(InlineKeyboardButton.builder().text("Ввести дату вручную").callbackData("ci:slot:manual").build()));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private static final DateTimeFormatter TIME_LABEL = DateTimeFormatter.ofPattern("HH:mm");
    private static final int TIME_PICKER_START_HOUR = 8;
    private static final int TIME_PICKER_END_HOUR = 22;

    private static InlineKeyboardMarkup createInterviewTimePickerKeyboard(
            LocalDate date, Map<Integer, EnumSet<InterviewRequestStatus>> busyStatuses, Set<Integer> availableHours, LocalDateTime now) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();
        for (int hour = TIME_PICKER_START_HOUR; hour < TIME_PICKER_END_HOUR; hour++) {
            LocalDateTime slotAt = date.atTime(hour, 0);
            boolean past = slotAt.isBefore(now);
            EnumSet<InterviewRequestStatus> busy = busyStatuses != null
                    ? busyStatuses.getOrDefault(hour, EnumSet.noneOf(InterviewRequestStatus.class))
                    : EnumSet.noneOf(InterviewRequestStatus.class);
            String timeStr = LocalTime.of(hour, 0).format(TIME_LABEL);
            String label;
            String callback;
            if (past) {
                label = timeStr;
                callback = "ci:timepast";
            } else if (!busy.isEmpty()) {
                StringBuilder lb = new StringBuilder(timeStr);
                TelegramScheduleUi.appendDominantInterviewStatusIcon(lb, busy);
                label = lb.toString();
                callback = "ci:noop";
            } else if (availableHours != null && availableHours.contains(hour)) {
                label = timeStr + " " + TelegramScheduleUi.DAY_HAS_ACTIVITY;
                callback = "ci:time:" + date + ":" + hour;
            } else {
                label = timeStr;
                callback = "ci:time:" + date + ":" + hour;
            }
            row.add(InlineKeyboardButton.builder().text(label).callbackData(callback).build());
            if (row.size() == 4) {
                rows.add(new InlineKeyboardRow(row));
                row = new ArrayList<>();
            }
        }
        if (!row.isEmpty()) rows.add(new InlineKeyboardRow(row));
        rows.add(new InlineKeyboardRow(InlineKeyboardButton.builder().text("← Другой день").callbackData("ci:slot:back").build()));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private static InlineKeyboardMarkup buildPartnerKeyboard(List<User> partners) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (User u : partners) {
            String label = u.getUsername() != null ? "@" + u.getUsername() : ("User " + u.getId());
            if (label.length() > 64) label = label.substring(0, 61) + "...";
            rows.add(new InlineKeyboardRow(
                    InlineKeyboardButton.builder().text(label).callbackData("ci:partner:" + u.getId()).build()));
        }
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("Создать слот без партнёра").callbackData("ci:partner:self").build()));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private static InlineKeyboardMarkup slotsKeyboardForDate(List<AvailableSlotDto> fullSlots, LocalDate date) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (int i = 0; i < fullSlots.size(); i++) {
            AvailableSlotDto s = fullSlots.get(i);
            if (!s.dateTime().toLocalDate().equals(date)) continue;
            String label = SLOT_LABEL.format(s.dateTime()) + " — " + s.partnerLabel();
            if (label.length() > 64) label = label.substring(0, 61) + "...";
            rows.add(new InlineKeyboardRow(
                    InlineKeyboardButton.builder().text(label).callbackData("ci:slot:" + i).build()));
        }
        rows.add(new InlineKeyboardRow(InlineKeyboardButton.builder().text("← Другой день").callbackData("ci:slot:back").build()));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    /**
     * Запись на открытый слот: заявка сразу принимается от имени владельца слота, создаётся собеседование,
     * оба получают уведомление (без кнопок «Принять / Отклонить»).
     */
    private void bookOpenSlotAndNotify(
            Long joinerChatId,
            Long candidateUserId,
            Long interviewerUserId,
            Language language,
            InterviewFormat format,
            LocalDateTime dateTime,
            int durationMinutes,
            String levelInfoExtra,
            Long openSlotRequestId,
            TelegramClient telegramClient) throws TelegramApiException {
        User joiner = userService.getUserById(candidateUserId);
        User creator = userService.getUserById(interviewerUserId);
        if (joiner == null || creator == null) {
            throw new UserNotFoundException("Joiner or slot owner not found");
        }
        Long creatorTg = creator.getTelegramId();
        if (creatorTg == null) {
            throw new IllegalArgumentException("Interview slot owner has no telegram id");
        }
        if (openSlotRequestId == null) {
            throw new IllegalArgumentException("Open slot request id required for booking");
        }
        InterviewRequest accepted = interviewRequestService.completeOpenSlotWithJoiner(
                openSlotRequestId,
                candidateUserId,
                language,
                format,
                dateTime,
                durationMinutes,
                LocalDateTime.now(clock));

        Interview created = interviewService.createInterview(
                candidateUserId,
                accepted.getSlotOwner().getId(),
                accepted.getLanguage(),
                accepted.getLevel(),
                accepted.getFormat(),
                accepted.getDateTime(),
                accepted.getDurationMinutes(),
                true);
        Interview forVideo = interviewService.getInterviewWithParticipants(created.getId());
        String meetingLinkBlock = videoMeetingBlock(forVideo);
        String dtStr = DT_FORMAT.format(dateTime);
        String creatorLabel = creator.getUsername() != null ? "@" + creator.getUsername() : "пользователь #" + interviewerUserId;
        String joinerLabel = joiner.getUsername() != null ? "@" + joiner.getUsername() : "пользователь #" + candidateUserId;

        telegramClient.execute(SendMessage.builder()
                .chatId(joinerChatId)
                .text("Запись подтверждена. Собеседование создано.\n"
                        + "Старт: " + dtStr + "\n"
                        + "Язык: " + language
                        + levelInfoExtra + "\n"
                        + "Партнёр: " + creatorLabel
                        + meetingLinkBlock)
                .replyMarkup(ChatMenuKeyboardBuilder.buildPersistentKeyboard())
                .build());

        telegramClient.execute(SendMessage.builder()
                .chatId(creatorTg)
                .text("К вашему слоту записался партнёр.\n"
                        + "Старт: " + dtStr + "\n"
                        + "Язык: " + language
                        + levelInfoExtra + "\n"
                        + "Партнёр: " + joinerLabel
                        + meetingLinkBlock)
                .replyMarkup(ChatMenuKeyboardBuilder.buildPersistentKeyboard())
                .build());
    }

    private void handleInterviewRequestCallback(Long chatId, TelegramClient telegramClient) throws TelegramApiException {
        // Старые кнопки ir:accept / ir:decline после удаления второго участника из заявок.
        telegramClient.execute(SendMessage.builder()
                .chatId(chatId)
                .text("Это действие устарело. Запись на слот — через «Доступные слоты»; открытый слот — только на себя в «Создать собеседование».")
                .replyMarkup(ChatMenuKeyboardBuilder.buildPersistentKeyboard())
                .build());
        sendMainMenu(chatId, telegramClient);
    }

    /**
     * В деталях часа расписания: при наличии согласования не показываем устаревшую PENDING на тот же час.
     */
    private static List<InterviewRequest> compactHourDetailsHidePendingWhenAccepted(List<InterviewRequest> bookedList) {
        if (bookedList == null || bookedList.isEmpty()) {
            return bookedList == null ? List.of() : bookedList;
        }
        boolean hasAccepted = bookedList.stream().anyMatch(r -> r.getStatus() == InterviewRequestStatus.ACCEPTED);
        if (!hasAccepted) {
            return bookedList;
        }
        return bookedList.stream()
                .filter(r -> r.getStatus() != InterviewRequestStatus.PENDING)
                .toList();
    }

    /**
     * Часы, где у пользователя уже есть заявка или согласованное собеседование (по статусу).
     */
    private Map<Integer, EnumSet<InterviewRequestStatus>> buildBusyStatusesForDate(Long userId, LocalDate date) {
        if (userId == null) return Map.of();
        List<InterviewRequest> requests = interviewRequestService.getUserRequests(userId, null);
        Map<Integer, EnumSet<InterviewRequestStatus>> busy = new java.util.HashMap<>();
        for (InterviewRequest i : requests) {
            if (i.getStatus() == InterviewRequestStatus.DECLINED
                    || i.getStatus() == InterviewRequestStatus.CANCELLED) {
                continue;
            }
            if (i.getDateTime() == null || !i.getDateTime().toLocalDate().equals(date)) {
                continue;
            }
            busy.computeIfAbsent(i.getDateTime().getHour(), h -> EnumSet.noneOf(InterviewRequestStatus.class))
                    .add(i.getStatus());
        }
        for (Interview iv : interviewService.getUserInterviews(userId, InterviewStatus.SCHEDULED)) {
            if (iv.getDateTime() == null || !iv.getDateTime().toLocalDate().equals(date)) {
                continue;
            }
            busy.computeIfAbsent(iv.getDateTime().getHour(), h -> EnumSet.noneOf(InterviewRequestStatus.class))
                    .add(InterviewRequestStatus.ACCEPTED);
        }
        return busy;
    }

    private void handleInterviewCalendarCallback(Long chatId,
                                                 Integer messageId,
                                                 String data,
                                                 org.telegram.telegrambots.meta.api.objects.User fromUser,
                                                 TelegramClient telegramClient) throws TelegramApiException {
        if (data.equals("ic:noop")) return;

        var state = stateService.getInterviewCalendar(chatId).orElse(null);
        if (state == null) {
            String text = "Сессия истекла. Откройте календарь заново: /schedule";
            org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup markup = ChatMenuKeyboardBuilder.buildPersistentKeyboard();
            if (messageId != null) {
                telegramClient.execute(EditMessageText.builder()
                        .chatId(chatId)
                        .messageId(messageId)
                        .text(text)
                        .build());
            } else {
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text(text)
                        .replyMarkup(markup)
                        .build());
            }
            return;
        }

        if (data.equals("ic:close")) {
            stateService.clearInterviewCalendar(chatId);
            sendMainMenu(chatId, telegramClient);
            return;
        }

        if (data.equals("ic:menu") || data.startsWith("ic:filter:")) {
            state.selectedDate = null;
            showInterviewCalendarMonth(chatId, messageId, state, telegramClient);
            return;
        }

        if (data.startsWith("ic:calnav:")) {
            String ym = data.substring("ic:calnav:".length());
            String[] parts = ym.split("-");
            if (parts.length != 2) return;
            int y = Integer.parseInt(parts[0]);
            int m = Integer.parseInt(parts[1]);
            state.calendarYear = y;
            state.calendarMonth = m;
            state.selectedDate = null;
            showInterviewCalendarMonth(chatId, messageId, state, telegramClient);
            return;
        }

        if (data.equals("ic:back")) {
            state.selectedDate = null;
            showInterviewCalendarMonth(chatId, messageId, state, telegramClient);
            return;
        }

        if (data.startsWith("ic:cancelreq:")) {
            if (fromUser == null) {
                telegramClient.execute(SendMessage.builder().chatId(chatId).text("Не удалось определить пользователя.").build());
                return;
            }
            Long requestId;
            try {
                requestId = Long.parseLong(data.substring("ic:cancelreq:".length()));
            } catch (NumberFormatException e) {
                return;
            }
            try {
                InterviewRequest before = interviewRequestService.getUserRequests(state.userId, null).stream()
                        .filter(r -> requestId.equals(r.getId()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Request not found"));
                User actor = userService.getUserByTelegramId(fromUser.getId());
                if (actor == null) {
                    telegramClient.execute(SendMessage.builder().chatId(chatId).text("Пользователь не найден.").build());
                    return;
                }
                java.util.Optional<Interview> linkedInterview = before.getStatus() == InterviewRequestStatus.ACCEPTED
                        ? findScheduledInterviewByRequest(before)
                        : java.util.Optional.empty();
                InterviewRequest cancelled = interviewRequestService.cancel(requestId, fromUser.getId(), LocalDateTime.now(clock));
                boolean wasAccepted = before.getStatus() == InterviewRequestStatus.ACCEPTED;
                if (wasAccepted) {
                    linkedInterview.ifPresent(i -> interviewService.cancelInterview(i.getId()));
                }
                User notifyUser = null;
                if (linkedInterview.isPresent()) {
                    Interview iv = linkedInterview.get();
                    if (java.util.Objects.equals(before.getSlotOwner().getId(), actor.getId())) {
                        notifyUser = iv.getCandidate();
                    } else {
                        notifyUser = before.getSlotOwner();
                    }
                }
                if (notifyUser != null && notifyUser.getTelegramId() != null) {
                    String title = wasAccepted
                            ? "Партнёр отменил согласованное собеседование."
                            : "Партнёр отменил заявку на собеседование.";
                    sendNotification(telegramClient, notifyUser.getTelegramId(),
                            title + "\n"
                                    + "Дата/время: " + DT_FORMAT.format(before.getDateTime()) + "\n"
                                    + "Язык: " + before.getLanguage());
                }
                telegramClient.execute(SendMessage.builder().chatId(chatId).text("Заявка отменена.").build());
                showInterviewCalendarMonth(chatId, messageId, state, telegramClient);
            } catch (InterviewRequestForbiddenException e) {
                telegramClient.execute(SendMessage.builder().chatId(chatId).text("Отменить может только участник заявки.").build());
            } catch (IllegalArgumentException e) {
                telegramClient.execute(SendMessage.builder().chatId(chatId).text("Заявка уже закрыта или не найдена.").build());
            }
            return;
        }

        if (data.startsWith("ic:cint:")) {
            if (fromUser == null) {
                telegramClient.execute(SendMessage.builder().chatId(chatId).text("Не удалось определить пользователя.").build());
                return;
            }
            long actorTelegramId = fromUser.getId();
            Long interviewId;
            try {
                interviewId = Long.parseLong(data.substring("ic:cint:".length()));
            } catch (NumberFormatException e) {
                return;
            }
            try {
                Interview iv = interviewService.getInterviewWithParticipants(interviewId);
                Long candTg = iv.getCandidate() != null ? iv.getCandidate().getTelegramId() : null;
                Long intTg = iv.getInterviewer() != null ? iv.getInterviewer().getTelegramId() : null;
                if (!Objects.equals(candTg, actorTelegramId) && !Objects.equals(intTg, actorTelegramId)) {
                    telegramClient.execute(SendMessage.builder().chatId(chatId).text("Отменить может только участник собеседования.").build());
                    return;
                }
                cancelOrphanAcceptedOpenSlotRequest(iv, actorTelegramId, LocalDateTime.now(clock));
                interviewService.cancelInterview(interviewId);
                telegramClient.execute(SendMessage.builder().chatId(chatId).text("Собеседование отменено.").build());
                showInterviewCalendarMonth(chatId, messageId, state, telegramClient);
            } catch (IllegalArgumentException e) {
                telegramClient.execute(SendMessage.builder().chatId(chatId).text("Собеседование не найдено.").build());
            } catch (InterviewRequestForbiddenException e) {
                telegramClient.execute(SendMessage.builder().chatId(chatId).text("Не удалось снять связанную заявку.").build());
            }
            return;
        }

        if (data.startsWith("ic:day:")) {
            String dateStr = data.substring("ic:day:".length());
            LocalDate day = LocalDate.parse(dateStr);
            state.selectedDate = day;

            LocalDate today = LocalDate.now(clock);
            List<InterviewRequest> scheduled = interviewRequestService.getUserRequests(state.userId, null);
            List<InterviewRequest> forDay = scheduled.stream()
                    .filter(i -> !i.getDateTime().toLocalDate().isBefore(today))
                    .filter(i -> i.getDateTime().toLocalDate().equals(day))
                    .filter(i -> i.getStatus() != InterviewRequestStatus.DECLINED
                            && i.getStatus() != InterviewRequestStatus.CANCELLED)
                    .sorted(java.util.Comparator.comparing(InterviewRequest::getDateTime))
                    .toList();
            Map<Integer, EnumSet<InterviewRequestStatus>> hourStatuses = new java.util.HashMap<>();
            for (InterviewRequest i : forDay) {
                int hour = i.getDateTime().getHour();
                EnumSet<InterviewRequestStatus> statuses = hourStatuses.computeIfAbsent(hour, h -> EnumSet.noneOf(InterviewRequestStatus.class));
                statuses.add(i.getStatus());
            }
            for (Interview iv : interviewService.getUserInterviews(state.userId, InterviewStatus.SCHEDULED)) {
                if (iv.getDateTime() == null || !iv.getDateTime().toLocalDate().equals(day)) {
                    continue;
                }
                if (iv.getDateTime().toLocalDate().isBefore(today)) {
                    continue;
                }
                int hour = iv.getDateTime().getHour();
                EnumSet<InterviewRequestStatus> statuses = hourStatuses.computeIfAbsent(hour, h -> EnumSet.noneOf(InterviewRequestStatus.class));
                statuses.add(InterviewRequestStatus.ACCEPTED);
            }

            java.time.format.DateTimeFormatter df = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy");
            String legend = hourStatuses.isEmpty() ? "" : TelegramScheduleUi.legendRequestAndConfirmed();
            String title = "Расписание — " + day.format(df) + " (" + formatDayOfWeekRu(day.getDayOfWeek()) + ")" + legend;

            InlineKeyboardMarkup keyboard = timePickerKeyboard(day, hourStatuses, LocalDateTime.now(clock));

            if (messageId != null) {
                try {
                    telegramClient.execute(EditMessageText.builder()
                            .chatId(chatId)
                            .messageId(messageId)
                            .text(title)
                            .replyMarkup(keyboard)
                            .build());
                } catch (TelegramApiException e) {
                    telegramClient.execute(SendMessage.builder()
                            .chatId(chatId)
                            .text(title)
                            .replyMarkup(keyboard)
                            .build());
                }
            } else {
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text(title)
                        .replyMarkup(keyboard)
                        .build());
            }
            return;
        }

        if (data.startsWith("ic:time:")) {
            String payload = data.substring("ic:time:".length());
            // payload: yyyy-MM-dd:HH
            String[] parts = payload.split(":", 2);
            if (parts.length != 2) return;
            LocalDate day = LocalDate.parse(parts[0]);
            int hour;
            try {
                hour = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                return;
            }

            LocalDate today = LocalDate.now(clock);
            List<InterviewRequest> scheduled = interviewRequestService.getUserRequests(state.userId, null);
            List<InterviewRequest> bookedList = scheduled.stream()
                    .filter(i -> !i.getDateTime().toLocalDate().isBefore(today))
                    .filter(i -> i.getDateTime().toLocalDate().equals(day))
                    .filter(i -> i.getDateTime().getHour() == hour)
                    .filter(i -> i.getStatus() != InterviewRequestStatus.DECLINED
                            && i.getStatus() != InterviewRequestStatus.CANCELLED)
                    .sorted(java.util.Comparator.comparing(InterviewRequest::getDateTime))
                    .toList();
            bookedList = compactHourDetailsHidePendingWhenAccepted(bookedList);
            bookedList = bookedList.stream()
                    .filter(r -> !(r.getStatus() == InterviewRequestStatus.ACCEPTED
                            && findScheduledInterviewByRequest(r).isPresent()))
                    .toList();
            final List<InterviewRequest> bookedListFinal = bookedList;

            List<Interview> interviewsAtHour = interviewService.getUserInterviews(state.userId, InterviewStatus.SCHEDULED).stream()
                    .filter(i -> i.getDateTime() != null
                            && !i.getDateTime().toLocalDate().isBefore(today)
                            && i.getDateTime().toLocalDate().equals(day)
                            && i.getDateTime().getHour() == hour)
                    .filter(i -> bookedListFinal.stream().noneMatch(r ->
                            findScheduledInterviewByRequest(r).map(iv -> iv.getId().equals(i.getId())).orElse(false)))
                    .sorted(java.util.Comparator.comparing(Interview::getDateTime))
                    .toList();

            String text;
            InlineKeyboardMarkup markup;
            if (bookedList.isEmpty() && interviewsAtHour.isEmpty()) {
                text = "На это время нет записей в выбранном разделе.";
                markup = timeBackKeyboard(day);
            } else {
                java.time.format.DateTimeFormatter timeFmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm");
                StringBuilder sb = new StringBuilder();
                for (InterviewRequest booked : bookedList) {
                    String time = booked.getDateTime().format(timeFmt);
                    if (sb.length() > 0) sb.append("\n\n");
                    User partner = schedulePartnerForViewer(booked, state.userId);
                    String partnerLabel = (partner != null && partner.getUsername() != null)
                            ? "@" + partner.getUsername()
                            : (partner != null ? "пользователь #" + partner.getId() : "—");
                    sb.append(TelegramScheduleUi.scheduleRequestDetailsLines(
                            booked.getStatus(),
                            partnerLabel,
                            booked.getLanguage(),
                            booked.getFormat(),
                            booked.getDurationMinutes(),
                            time));
                }
                for (Interview iv : interviewsAtHour) {
                    String time = iv.getDateTime().format(timeFmt);
                    if (sb.length() > 0) sb.append("\n\n");
                    User partner = state.userId.equals(iv.getInterviewer().getId())
                            ? iv.getCandidate()
                            : iv.getInterviewer();
                    String partnerLabel = (partner != null && partner.getUsername() != null)
                            ? "@" + partner.getUsername()
                            : (partner != null ? "пользователь #" + partner.getId() : "—");
                    sb.append(TelegramScheduleUi.scheduleRequestDetailsLines(
                            InterviewRequestStatus.ACCEPTED,
                            partnerLabel,
                            iv.getLanguage(),
                            iv.getFormat(),
                            iv.getDuration(),
                            time));
                }
                text = sb.toString();
                markup = timeDetailsKeyboard(day, bookedList, interviewsAtHour);
            }

            if (messageId != null) {
                try {
                    telegramClient.execute(EditMessageText.builder()
                            .chatId(chatId)
                            .messageId(messageId)
                            .text(text)
                            .replyMarkup(markup)
                            .build());
                } catch (TelegramApiException e) {
                    telegramClient.execute(SendMessage.builder()
                            .chatId(chatId)
                            .text(text)
                            .replyMarkup(markup)
                            .build());
                }
            } else {
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text(text)
                        .replyMarkup(markup)
                        .build());
            }
            return;
        }
    }

    private void showInterviewCalendarMonth(Long chatId, Integer messageId, InterviewCalendarState state, TelegramClient telegramClient) throws TelegramApiException {
        interviewCalendarPresenter.sendMonthView(chatId, messageId, state, telegramClient);
    }

    private InlineKeyboardMarkup dayBackKeyboard() {
        return InlineKeyboardMarkup.builder()
                .keyboard(List.of(
                        new InlineKeyboardRow(
                                InlineKeyboardButton.builder().text("⬅️ Календарь").callbackData("ic:back").build()
                        )
                ))
                .build();
    }

    /**
     * Сетка часов расписания: как при записи на слот — прошедшее время того же дня даёт {@code ic:timepast},
     * занятые часы с эмодзи статуса, свободные — только HH:mm.
     */
    private InlineKeyboardMarkup timePickerKeyboard(
            LocalDate date,
            Map<Integer, EnumSet<InterviewRequestStatus>> hourStatuses,
            LocalDateTime now) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();

        for (int hour = TIME_PICKER_START_HOUR; hour < TIME_PICKER_END_HOUR; hour++) {
            LocalDateTime slotAt = date.atTime(hour, 0);
            boolean past = slotAt.isBefore(now);
            EnumSet<InterviewRequestStatus> statuses = hourStatuses != null
                    ? hourStatuses.getOrDefault(hour, EnumSet.noneOf(InterviewRequestStatus.class))
                    : EnumSet.noneOf(InterviewRequestStatus.class);
            String timeStr = LocalTime.of(hour, 0).format(TIME_LABEL);
            String label;
            String callback;
            if (past) {
                label = timeStr;
                callback = "ic:timepast";
            } else if (!statuses.isEmpty()) {
                StringBuilder lb = new StringBuilder(timeStr);
                TelegramScheduleUi.appendDominantInterviewStatusIcon(lb, statuses);
                label = lb.toString();
                callback = "ic:time:" + date + ":" + hour;
            } else {
                label = timeStr;
                callback = "ic:time:" + date + ":" + hour;
            }
            row.add(InlineKeyboardButton.builder()
                    .text(label)
                    .callbackData(callback)
                    .build());
            if (row.size() == 4) {
                rows.add(new InlineKeyboardRow(row));
                row = new ArrayList<>();
            }
        }
        if (!row.isEmpty()) rows.add(new InlineKeyboardRow(row));

        rows.add(new InlineKeyboardRow(InlineKeyboardButton.builder()
                .text("← Другой день")
                .callbackData("ic:back")
                .build()));

        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private InlineKeyboardMarkup timeBackKeyboard(LocalDate date) {
        return InlineKeyboardMarkup.builder().keyboard(List.of(
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder()
                                .text("⬅️ К слотам")
                                .callbackData("ic:day:" + date)
                                .build()
                )
        )).build();
    }

    private InlineKeyboardMarkup timeDetailsKeyboard(LocalDate date, List<InterviewRequest> requests, List<Interview> interviews) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (InterviewRequest request : requests) {
            if (request.getStatus() == InterviewRequestStatus.DECLINED
                    || request.getStatus() == InterviewRequestStatus.CANCELLED) {
                continue;
            }
            rows.add(new InlineKeyboardRow(
                    InlineKeyboardButton.builder()
                            .text("Отменить заявку #" + request.getId())
                            .callbackData("ic:cancelreq:" + request.getId())
                            .build()
            ));
        }
        for (Interview iv : interviews) {
            rows.add(new InlineKeyboardRow(
                    InlineKeyboardButton.builder()
                            .text("Отменить собеседование #" + iv.getId())
                            .callbackData("ic:cint:" + iv.getId())
                            .build()
            ));
        }
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("⬅️ К слотам").callbackData("ic:day:" + date).build()
        ));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private void handleMainMenuCallback(Long chatId, String data, org.telegram.telegrambots.meta.api.objects.User fromTelegram, TelegramClient telegramClient) throws TelegramApiException {
        if (fromTelegram == null) {
            telegramClient.execute(SendMessage.builder().chatId(chatId).text("Ошибка: не удалось определить пользователя.").replyMarkup(ChatMenuKeyboardBuilder.buildPersistentKeyboard()).build());
            return;
        }
        long telegramId = fromTelegram.getId();
        String username = fromTelegram.getUserName() != null ? fromTelegram.getUserName() : fromTelegram.getFirstName();
        User user = userService.registerUser(telegramId, username != null ? username : "user");

        switch (data) {
            case "cmd:create_interview" -> {
                stateService.startCreateInterview(chatId, user.getId());
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text("Записаться на взаимный час: выберите направление.")
                        .replyMarkup(createInterviewLanguageKeyboard())
                        .build());
            }
            case "cmd:available_slots" -> {
                stateService.clearCreateInterview(chatId);
                stateService.startCreateInterview(chatId, user.getId());
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text("Доступные слоты: выберите направление.")
                        .replyMarkup(availableSlotsLanguageKeyboard())
                        .build());
            }
            case "cmd:schedule" -> {
                var calendarState = stateService.startInterviewCalendar(chatId, user.getId());
                interviewCalendarPresenter.sendMonthView(chatId, null, calendarState, telegramClient, true);
            }
            case "cmd:help" -> {
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text(HelpCommandHandler.HELP_TEXT)
                        .replyMarkup(ChatMenuKeyboardBuilder.buildPersistentKeyboard())
                        .build());
            }
            default -> sendMainMenu(chatId, telegramClient);
        }
    }

    private void handleAvailableSlotsCallback(Long chatId, String data, TelegramClient telegramClient) throws TelegramApiException {
        if ("as:noop".equals(data)) return;
        if ("as:pick_lang".equals(data)) {
            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("Выберите направление.")
                    .replyMarkup(availableSlotsLanguageKeyboard())
                    .build());
            return;
        }
        if ("as:cancel".equals(data)) {
            stateService.clearCreateInterview(chatId);
            telegramClient.execute(SendMessage.builder().chatId(chatId).text("Ок, закрыто.").build());
            sendMainMenu(chatId, telegramClient);
            return;
        }

        CreateInterviewState state = stateService.getCreateInterview(chatId).orElse(null);
        if (state == null || state.candidateUserId == null) {
            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("Сессия истекла. Нажмите «Доступные слоты» и выберите направление заново.")
                    .replyMarkup(ChatMenuKeyboardBuilder.buildPersistentKeyboard())
                    .build());
            return;
        }

        if (data.startsWith("as:lang:")) {
            state.language = Language.valueOf(data.substring("as:lang:".length()));
            state.format = InterviewFormat.TECHNICAL;
            state.level = null;
            state.openSlotRequestId = null;
            state.interviewerUserId = null;
            state.dateTime = null;
            state.durationMinutes = null;

            List<AvailableSlotDto> slots = interviewService.getAvailableSlotsAsCandidate(
                    state.candidateUserId, state.language, null, 14).stream()
                    .filter(s -> !s.dateTime().isBefore(LocalDateTime.now(clock)))
                    .sorted(java.util.Comparator.comparing(AvailableSlotDto::dateTime))
                    .toList();
            state.availableSlots = slots;

            if (slots.isEmpty()) {
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text("По выбранному направлению пока нет доступных слотов от других участников.")
                        .replyMarkup(availableSlotsLanguageKeyboard())
                        .build());
                return;
            }

            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(renderAvailableSlots(state.language, slots, clock.getZone().getId()))
                    .replyMarkup(availableSlotsKeyboard(slots))
                    .build());
            return;
        }

        if (data.startsWith("as:slot:")) {
            if (state.availableSlots == null || state.availableSlots.isEmpty()) {
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text("Список слотов пуст. Выберите направление заново.")
                        .replyMarkup(availableSlotsLanguageKeyboard())
                        .build());
                return;
            }

            int index;
            try {
                index = Integer.parseInt(data.substring("as:slot:".length()));
            } catch (NumberFormatException e) {
                return;
            }
            if (index < 0 || index >= state.availableSlots.size()) {
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text("Слот не найден. Выберите из актуального списка.")
                        .replyMarkup(availableSlotsKeyboard(state.availableSlots))
                        .build());
                return;
            }

            AvailableSlotDto slot = state.availableSlots.get(index);
            if (slot.dateTime().isBefore(LocalDateTime.now(clock))) {
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text("Этот слот уже в прошлом. Выберите другой.")
                        .replyMarkup(availableSlotsKeyboard(state.availableSlots))
                        .build());
                return;
            }

            state.dateTime = slot.dateTime();
            state.durationMinutes = 60;
            state.openSlotRequestId = slot.openSlotRequestId();
            state.interviewerUserId = slot.partnerUserId();
            state.step = CreateInterviewState.Step.CONFIRM;

            String summary = "Подтвердить запись на встречу?\n"
                    + "Язык: " + state.language + "\n"
                    + "Уровень: " + levelLabel(slot.partnerLevel()) + "\n"
                    + "Дата/время: " + DT_FORMAT.format(slot.dateTime()) + "\n"
                    + "Партнёр: " + slot.partnerLabel();
            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(summary)
                    .replyMarkup(availableSlotsConfirmKeyboard())
                    .build());
            return;
        }

        if (data.startsWith("as:confirm:")) {
            String action = data.substring("as:confirm:".length());
            if ("no".equals(action)) {
                if (state.availableSlots == null || state.availableSlots.isEmpty()) {
                    telegramClient.execute(SendMessage.builder()
                            .chatId(chatId)
                            .text("Запись отменена.")
                            .replyMarkup(availableSlotsLanguageKeyboard())
                            .build());
                    return;
                }
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text("Ок, не записываю. Выберите другой слот.")
                        .replyMarkup(availableSlotsKeyboard(state.availableSlots))
                        .build());
                return;
            }

            if (!"yes".equals(action) || state.openSlotRequestId == null || state.dateTime == null || state.durationMinutes == null) {
                return;
            }

            if (hasScheduledConflictAtSameTime(state.candidateUserId, state.openSlotRequestId, state.dateTime, state.durationMinutes)) {
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text("На этот временной слот у вас уже есть согласованное собеседование. Выберите другое время.")
                        .replyMarkup(availableSlotsKeyboard(state.availableSlots != null ? state.availableSlots : List.of()))
                        .build());
                return;
            }

            try {
                bookOpenSlotAndNotify(
                        chatId,
                        state.candidateUserId,
                        state.interviewerUserId,
                        state.language,
                        InterviewFormat.TECHNICAL,
                        state.dateTime,
                        state.durationMinutes,
                        "",
                        state.openSlotRequestId,
                        telegramClient);
                stateService.clearCreateInterview(chatId);
                sendMainMenu(chatId, telegramClient);
            } catch (InterviewConflictException e) {
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text("Этот слот уже занят или конфликтует по времени. Выберите другой.")
                        .replyMarkup(availableSlotsKeyboard(state.availableSlots != null ? state.availableSlots : List.of()))
                        .build());
            } catch (IllegalArgumentException e) {
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text("Не удалось отправить заявку: конфликт по времени или уже есть такая заявка.")
                        .replyMarkup(availableSlotsKeyboard(state.availableSlots != null ? state.availableSlots : List.of()))
                        .build());
            } catch (UserNotFoundException e) {
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text("Ошибка: пользователь не найден. Попробуйте выбрать слот заново.")
                        .replyMarkup(availableSlotsKeyboard(state.availableSlots != null ? state.availableSlots : List.of()))
                        .build());
            }
        }
    }

    private void sendMainMenu(Long chatId, TelegramClient telegramClient) throws TelegramApiException {
        telegramClient.execute(SendMessage.builder()
                .chatId(chatId)
                .text(MainMenuBuilder.getShortMenuPrompt())
                .replyMarkup(ChatMenuKeyboardBuilder.buildPersistentKeyboard())
                .build());
    }

    private String buildInterviewsText(Long userId) {
        List<InterviewRequest> all = interviewRequestService.getUserRequests(userId, null);
        var now = LocalDateTime.now(clock);
        var upcoming = all.stream().filter(i -> i.getDateTime().isAfter(now)).toList();
        var past = all.stream().filter(i -> i.getDateTime().isBefore(now)).toList();
        StringBuilder sb = new StringBuilder();
        sb.append("Ваши заявки на собеседования\n(время — ").append(clock.getZone().getId()).append(")\n\n");
        sb.append("Предстоящие заявки:\n");
        if (upcoming.isEmpty()) {
            sb.append("- нет\n");
        } else {
            for (InterviewRequest i : upcoming) {
                sb.append("- ").append(DT_FORMAT.format(i.getDateTime()))
                        .append(" • ").append(i.getLanguage())
                        .append(" • ").append(i.getFormat())
                        .append(" • ").append(i.getDurationMinutes()).append(" мин")
                        .append(" • ").append(i.getStatus())
                        .append("\n");
            }
        }
        sb.append("\nПрошедшие заявки:\n");
        if (past.isEmpty()) {
            sb.append("- нет\n");
        } else {
            for (InterviewRequest i : past) {
                sb.append("- ").append(DT_FORMAT.format(i.getDateTime()))
                        .append(" • ").append(i.getStatus())
                        .append(" • ").append(i.getLanguage())
                        .append(" • ").append(i.getFormat())
                        .append("\n");
            }
        }
        return sb.toString();
    }

    private static InlineKeyboardMarkup createInterviewLevelKeyboard() {
        var junior = InlineKeyboardButton.builder().text("Junior").callbackData("ci:level:JUNIOR").build();
        var middle = InlineKeyboardButton.builder().text("Middle").callbackData("ci:level:MIDDLE").build();
        var senior = InlineKeyboardButton.builder().text("Senior").callbackData("ci:level:SENIOR").build();
        var cancel = InlineKeyboardButton.builder().text("Отмена").callbackData("ci:cancel").build();
        return InlineKeyboardMarkup.builder().keyboard(List.of(
                new InlineKeyboardRow(junior, middle, senior),
                new InlineKeyboardRow(cancel)
        )).build();
    }

    private static String levelLabel(Level level) {
        if (level == null) return "—";
        return switch (level) {
            case JUNIOR -> "Junior";
            case MIDDLE -> "Middle";
            case SENIOR -> "Senior";
        };
    }

    private static InlineKeyboardMarkup createInterviewLanguageKeyboard() {
        var java = InlineKeyboardButton.builder().text("Java").callbackData("ci:lang:JAVA").build();
        var python = InlineKeyboardButton.builder().text("Python").callbackData("ci:lang:PYTHON").build();
        var csharp = InlineKeyboardButton.builder().text("C#").callbackData("ci:lang:CSHARP").build();
        var cpp = InlineKeyboardButton.builder().text("C++").callbackData("ci:lang:CPP").build();
        var algorithms = InlineKeyboardButton.builder().text("Algorithms").callbackData("ci:lang:ALGORITHMS").build();
        var productManager = InlineKeyboardButton.builder().text("Product Manager").callbackData("ci:lang:PRODUCT_MANAGER").build();
        var js = InlineKeyboardButton.builder().text("JavaScript").callbackData("ci:lang:JAVASCRIPT").build();
        var kotlin = InlineKeyboardButton.builder().text("Kotlin").callbackData("ci:lang:KOTLIN").build();
        var swift = InlineKeyboardButton.builder().text("Swift").callbackData("ci:lang:SWIFT").build();
        var go = InlineKeyboardButton.builder().text("Go").callbackData("ci:lang:GO").build();
        var qa = InlineKeyboardButton.builder().text("QA").callbackData("ci:lang:QA").build();
        var data = InlineKeyboardButton.builder().text("Data Analytics").callbackData("ci:lang:DATA_ANALYTICS").build();
        var ba = InlineKeyboardButton.builder().text("Business Analysis").callbackData("ci:lang:BUSINESS_ANALYSIS").build();
        var sa = InlineKeyboardButton.builder().text("System Analysis").callbackData("ci:lang:SYSTEM_ANALYSIS").build();
        var infosec = InlineKeyboardButton.builder().text("ИБ").callbackData("ci:lang:INFORMATION_SECURITY").build();
        var cancel = InlineKeyboardButton.builder().text("Отмена").callbackData("ci:cancel").build();
        return InlineKeyboardMarkup.builder().keyboard(List.of(
                new InlineKeyboardRow(java, csharp, cpp),
                new InlineKeyboardRow(python, algorithms, productManager, js),
                new InlineKeyboardRow(kotlin, swift),
                new InlineKeyboardRow(go, qa),
                new InlineKeyboardRow(data, ba),
                new InlineKeyboardRow(sa, infosec),
                new InlineKeyboardRow(cancel)
        )).build();
    }

    private static InlineKeyboardMarkup availableSlotsLanguageKeyboard() {
        var java = InlineKeyboardButton.builder().text("Java").callbackData("as:lang:JAVA").build();
        var csharp = InlineKeyboardButton.builder().text("C#").callbackData("as:lang:CSHARP").build();
        var cpp = InlineKeyboardButton.builder().text("C++").callbackData("as:lang:CPP").build();
        var python = InlineKeyboardButton.builder().text("Python").callbackData("as:lang:PYTHON").build();
        var algorithms = InlineKeyboardButton.builder().text("Algorithms").callbackData("as:lang:ALGORITHMS").build();
        var productManager = InlineKeyboardButton.builder().text("Product Manager").callbackData("as:lang:PRODUCT_MANAGER").build();
        var js = InlineKeyboardButton.builder().text("JavaScript").callbackData("as:lang:JAVASCRIPT").build();
        var kotlin = InlineKeyboardButton.builder().text("Kotlin").callbackData("as:lang:KOTLIN").build();
        var swift = InlineKeyboardButton.builder().text("Swift").callbackData("as:lang:SWIFT").build();
        var go = InlineKeyboardButton.builder().text("Go").callbackData("as:lang:GO").build();
        var qa = InlineKeyboardButton.builder().text("QA").callbackData("as:lang:QA").build();
        var data = InlineKeyboardButton.builder().text("Data Analytics").callbackData("as:lang:DATA_ANALYTICS").build();
        var ba = InlineKeyboardButton.builder().text("Business Analysis").callbackData("as:lang:BUSINESS_ANALYSIS").build();
        var sa = InlineKeyboardButton.builder().text("System Analysis").callbackData("as:lang:SYSTEM_ANALYSIS").build();
        var infosec = InlineKeyboardButton.builder().text("ИБ").callbackData("as:lang:INFORMATION_SECURITY").build();
        var cancel = InlineKeyboardButton.builder().text("Отмена").callbackData("as:cancel").build();
        return InlineKeyboardMarkup.builder().keyboard(List.of(
                new InlineKeyboardRow(java, csharp, cpp),
                new InlineKeyboardRow(python, algorithms, productManager, js),
                new InlineKeyboardRow(kotlin, swift),
                new InlineKeyboardRow(go, qa),
                new InlineKeyboardRow(data, ba),
                new InlineKeyboardRow(sa, infosec),
                new InlineKeyboardRow(cancel)
        )).build();
    }

    private static InlineKeyboardMarkup availableSlotsKeyboard(List<AvailableSlotDto> slots) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            AvailableSlotDto slot = slots.get(i);
            String label = slot.dateTime().format(DateTimeFormatter.ofPattern("dd.MM HH:mm"))
                    + " • " + levelLabel(slot.partnerLevel());
            rows.add(new InlineKeyboardRow(
                    InlineKeyboardButton.builder()
                            .text(label)
                            .callbackData("as:slot:" + i)
                            .build()
            ));
        }
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("Сменить направление").callbackData("as:pick_lang").build(),
                InlineKeyboardButton.builder().text("Закрыть").callbackData("as:cancel").build()
        ));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private static InlineKeyboardMarkup availableSlotsConfirmKeyboard() {
        return InlineKeyboardMarkup.builder().keyboard(List.of(
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("Подтвердить запись").callbackData("as:confirm:yes").build(),
                        InlineKeyboardButton.builder().text("Назад").callbackData("as:confirm:no").build()
                )
        )).build();
    }

    private static String renderAvailableSlots(Language language, List<AvailableSlotDto> slots, String zoneId) {
        StringBuilder sb = new StringBuilder("Доступные слоты");
        if (language != null) {
            sb.append(" (").append(language).append(")");
        }
        sb.append(":\n");
        sb.append("Часовой пояс: ").append(zoneId).append("\n\n");

        appendLevelGroup(sb, slots, Level.JUNIOR);
        appendLevelGroup(sb, slots, Level.MIDDLE);
        appendLevelGroup(sb, slots, Level.SENIOR);
        return sb.toString().trim();
    }

    private static void appendLevelGroup(StringBuilder sb, List<AvailableSlotDto> slots, Level level) {
        sb.append(levelLabel(level)).append(":\n");
        var grouped = slots.stream()
                .filter(s -> level == s.partnerLevel())
                .sorted(java.util.Comparator.comparing(AvailableSlotDto::dateTime))
                .toList();
        if (grouped.isEmpty()) {
            sb.append("- нет\n\n");
            return;
        }
        for (AvailableSlotDto slot : grouped) {
            sb.append("- ")
                    .append(slot.dateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                    .append(" • ")
                    .append(slot.partnerLabel())
                    .append("\n");
        }
        sb.append("\n");
    }

    private boolean hasScheduledConflictAtSameTime(Long userId, Long excludeRequestId, LocalDateTime start, int durationMinutes) {
        if (userId == null || start == null) return false;
        LocalDateTime end = start.plusMinutes(durationMinutes);
        List<InterviewRequest> requests = interviewRequestService.getUserRequests(userId, null);
        if (requests != null) {
            boolean requestOverlap = requests.stream()
                    .filter(r -> r.getStatus() != InterviewRequestStatus.DECLINED)
                    .filter(r -> r.getStatus() != InterviewRequestStatus.CANCELLED)
                    .filter(r -> excludeRequestId == null || !excludeRequestId.equals(r.getId()))
                    .anyMatch(r -> {
                        LocalDateTime iStart = r.getDateTime();
                        LocalDateTime iEnd = iStart.plusMinutes(r.getDurationMinutes());
                        return iStart.isBefore(end) && start.isBefore(iEnd);
                    });
            if (requestOverlap) return true;
        }
        List<Interview> scheduled = interviewService.getUserInterviews(userId, InterviewStatus.SCHEDULED);
        if (scheduled == null) return false;
        return scheduled.stream().anyMatch(i -> {
            LocalDateTime iStart = i.getDateTime();
            LocalDateTime iEnd = iStart.plusMinutes(i.getDuration());
            return iStart.isBefore(end) && start.isBefore(iEnd);
        });
    }

    private static final String[] DAY_NAMES_RU = new String[]{"Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"};
    private static final String[] MONTH_NAMES_RU = new String[]{"Янв", "Фев", "Мар", "Апр", "Май", "Июн", "Июл", "Авг", "Сен", "Окт", "Ноя", "Дек"};

    private static String formatDayOfWeekRu(DayOfWeek d) {
        return DAY_NAMES_RU[d.getValue() - 1];
    }


    private void cancelOrphanAcceptedOpenSlotRequest(Interview interview, long actorTelegramId, LocalDateTime now) {
        Long ownerUserId = interview.getInterviewer().getId();
        interviewRequestService.getUserRequests(ownerUserId, null).stream()
                .filter(r -> r.getStatus() == InterviewRequestStatus.ACCEPTED)
                .filter(r -> r.getDateTime().equals(interview.getDateTime()))
                .filter(r -> Objects.equals(r.getDurationMinutes(), interview.getDuration()))
                .findFirst()
                .ifPresent(r -> interviewRequestService.cancel(r.getId(), actorTelegramId, now));
    }

    /**
     * Второй участник для экрана расписания: не совпадает с просматривающим.
     * Для заявки после записи на открытый слот второй участник берётся из {@link Interview}.
     */
    private User schedulePartnerForViewer(InterviewRequest request, Long viewerUserId) {
        if (request.getSlotOwner() == null || viewerUserId == null) {
            return null;
        }
        return findScheduledInterviewByRequest(request)
                .map(i -> {
                    Long ownerId = request.getSlotOwner().getId();
                    if (viewerUserId.equals(ownerId)) {
                        return i.getCandidate();
                    }
                    if (i.getCandidate() != null && viewerUserId.equals(i.getCandidate().getId())) {
                        return i.getInterviewer();
                    }
                    return null;
                })
                .orElse(null);
    }

    private Optional<Interview> findScheduledInterviewByRequest(InterviewRequest request) {
        if (request == null || request.getSlotOwner() == null || request.getSlotOwner().getId() == null) {
            return Optional.empty();
        }
        Long ownerId = request.getSlotOwner().getId();
        return interviewService.getUserInterviews(ownerId, InterviewStatus.SCHEDULED).stream()
                .filter(i -> i.getCandidate() != null && i.getInterviewer() != null)
                .filter(i -> ownerId.equals(i.getInterviewer().getId()))
                .filter(i -> i.getDateTime().equals(request.getDateTime()))
                .filter(i -> i.getDuration().equals(request.getDurationMinutes()))
                .filter(i -> i.getLanguage() == request.getLanguage())
                .filter(i -> i.getFormat() == request.getFormat())
                .findFirst();
    }

    /** Callback до выбора языка/формата (или отмена). */
    private static boolean allowsCreateInterviewCallbackWithoutLanguage(String data) {
        if ("ci:cancel".equals(data) || "ci:noop".equals(data)) {
            return true;
        }
        return data.startsWith("ci:lang:");
    }

    /**
     * Отправляет уведомление пользователю по его Telegram ID.
     * Молча игнорирует ошибки (пользователь мог заблокировать бота).
     */
    private static void sendNotification(TelegramClient telegramClient, Long telegramId, String text) {
        if (telegramId == null) return;
        try {
            telegramClient.execute(SendMessage.builder()
                    .chatId(telegramId)
                    .text(text)
                    .build());
        } catch (TelegramApiException e) {
            org.slf4j.LoggerFactory.getLogger(CallbackQueryHandler.class)
                    .warn("Не удалось отправить уведомление пользователю telegramId={}", telegramId, e);
        }
    }
}
