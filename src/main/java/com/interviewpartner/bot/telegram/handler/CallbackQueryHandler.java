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
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
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

    private final ConversationStateService stateService;
    private final InterviewService interviewService;
    private final UserService userService;
    private final InterviewRequestService interviewRequestService;
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
                handleInterviewCalendarCallback(chatId, messageId, safeData, telegramClient);
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
                handleInterviewRequestCallback(chatId, safeData, callback.getFrom(), telegramClient);
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
            if (state.candidateUserId != null) userService.updateUserLanguage(state.candidateUserId, state.language);
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
            if (state.candidateUserId != null && state.level != null) {
                userService.updateUserLevel(state.candidateUserId, state.level);
            }
            List<AvailableSlotDto> slots = interviewService.getAvailableSlotsAsCandidate(
                    state.candidateUserId, state.language, state.level, 14);
            if (slots == null) slots = List.of();
            state.availableSlots = slots;
            state.step = CreateInterviewState.Step.VIEW_SLOT_DATES;
            LocalDate today = LocalDate.now();
            LocalDate firstSlotDate = slots.stream()
                    .map(s -> s.dateTime().toLocalDate())
                    .min(LocalDate::compareTo)
                    .map(d -> d.isBefore(today) ? today : d)
                    .orElse(today);
            state.slotCalendarYear = firstSlotDate.getYear();
            state.slotCalendarMonth = firstSlotDate.getMonthValue();
            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("Выберите день в календаре (✓ — есть слоты). Затем выберите время.")
                    .replyMarkup(slotCalendarKeyboard(slots, state.slotCalendarYear, state.slotCalendarMonth))
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
                    .replyMarkup(slotCalendarKeyboard(slots, state.slotCalendarYear, state.slotCalendarMonth))
                    .build());
            return;
        }
        if (data.startsWith("ci:slotdate:")) {
            String dateStr = data.substring("ci:slotdate:".length());
            LocalDate picked = LocalDate.parse(dateStr);
            if (picked.isBefore(LocalDate.now())) {
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text("Нельзя выбрать дату в прошлом. Выберите сегодня или позже.")
                        .replyMarkup(slotCalendarKeyboard(state.availableSlots != null ? state.availableSlots : List.of(), state.slotCalendarYear, state.slotCalendarMonth))
                        .build());
                return;
            }
            state.selectedSlotDate = picked;
            String dayLabel = state.selectedSlotDate.format(DATE_BTN) + " (" + DAY_NAMES_RU[state.selectedSlotDate.getDayOfWeek().getValue() - 1] + ")";

            Map<Integer, CalendarRoleType> busyHours = buildBusyHoursForDate(state.candidateUserId, state.selectedSlotDate);

            // Часы, в которые есть доступный партнёр
            Set<Integer> availableHours = (state.availableSlots == null ? List.<AvailableSlotDto>of() : state.availableSlots)
                    .stream()
                    .filter(s -> s.dateTime().toLocalDate().equals(state.selectedSlotDate))
                    .map(s -> s.dateTime().getHour())
                    .collect(java.util.stream.Collectors.toSet());

            state.step = CreateInterviewState.Step.VIEW_SLOTS;

            StringBuilder legend = new StringBuilder();
            if (!availableHours.isEmpty()) legend.append("\n✓ — есть открытый слот другого участника");
            if (!busyHours.isEmpty()) {
                legend.append("\n").append(ROLE_CANDIDATE).append(" — в 1-й половине часа вы «кандидат»")
                        .append("  ").append(ROLE_INTERVIEWER).append(" — в 1-й половине вы «интервьюер»");
            }

            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(dayLabel + (legend.length() > 0 ? legend.toString() : ""))
                    .replyMarkup(createInterviewTimePickerKeyboard(state.selectedSlotDate, busyHours, availableHours, LocalDateTime.now(clock)))
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
                Map<Integer, CalendarRoleType> busyHours = buildBusyHoursForDate(state.candidateUserId, date);
                Set<Integer> availableHours = (state.availableSlots == null ? List.<AvailableSlotDto>of() : state.availableSlots)
                        .stream()
                        .filter(s -> s.dateTime().toLocalDate().equals(date))
                        .map(s -> s.dateTime().getHour())
                        .collect(java.util.stream.Collectors.toSet());
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text("Это время уже прошло. Выберите другое.")
                        .replyMarkup(createInterviewTimePickerKeyboard(date, busyHours, availableHours, LocalDateTime.now(clock)))
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
                state.joinInterviewId = matchedSlot.interviewId();
                state.interviewerUserId = matchedSlot.partnerUserId();
                state.step = CreateInterviewState.Step.CONFIRM;
                String partnerLabel = matchedSlot.partnerLabel()
                        + (matchedSlot.partnerLevel() != null ? " [" + levelLabel(matchedSlot.partnerLevel()) + "]" : "");
                boolean joining = state.joinInterviewId != null;
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
                // Нет готового слота — открытый часовой слот (создатель = интервьюер первой половины)
                state.interviewerUserId = state.candidateUserId;
                state.step = CreateInterviewState.Step.CONFIRM;
                String summary = "Пока никто не открыл это время. Создать открытый часовой слот на "
                        + DT_FORMAT.format(state.dateTime) + " (" + state.language + ")"
                        + (state.level != null ? " [" + levelLabel(state.level) + "]" : "") + "?\n"
                        + "Вы будете интервьюером первой половины часа, когда кто-то запишется.";
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
                var now = LocalDate.now();
                state.slotCalendarYear = now.getYear();
                state.slotCalendarMonth = now.getMonthValue();
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text("Выберите день в календаре, затем время:")
                        .replyMarkup(slotCalendarKeyboard(state.availableSlots != null ? state.availableSlots : List.of(), state.slotCalendarYear, state.slotCalendarMonth))
                        .build());
                return;
            }
            if ("back".equals(payload)) {
                state.step = CreateInterviewState.Step.VIEW_SLOT_DATES;
                List<AvailableSlotDto> slots = state.availableSlots != null ? state.availableSlots : List.of();
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text("Выберите день в календаре:")
                        .replyMarkup(slotCalendarKeyboard(slots, state.slotCalendarYear, state.slotCalendarMonth))
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
                        .replyMarkup(slotCalendarKeyboard(state.availableSlots, state.slotCalendarYear, state.slotCalendarMonth))
                        .build());
                return;
            }
            state.dateTime = slot.dateTime();
            state.durationMinutes = 60;
            state.joinInterviewId = slot.interviewId();
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
                    if (state.joinInterviewId != null) {
                        Long myId = state.candidateUserId;
                        Long creatorId = state.interviewerUserId;
                        User joiner = userService.getUserById(myId);
                        User creator = userService.getUserById(creatorId);
                        InterviewRequest request = interviewRequestService.createRequest(
                                myId,
                                creatorId,
                                state.language,
                                state.format,
                                state.dateTime,
                                state.durationMinutes
                        );

                        String creatorLabel = creator.getUsername() != null ? "@" + creator.getUsername() : "пользователь #" + creatorId;
                        String joinerLabel = joiner.getUsername() != null ? "@" + joiner.getUsername() : "пользователь #" + myId;
                        String dtStr = DT_FORMAT.format(state.dateTime);

                        String levelInfo = state.level != null ? "\nГрейд: " + levelLabel(state.level) : "";

                        telegramClient.execute(SendMessage.builder()
                                .chatId(chatId)
                                .text("Заявка отправлена интервьюеру.\n"
                                        + "Старт: " + dtStr + "\n"
                                        + "Язык: " + state.language
                                        + levelInfo + "\n"
                                        + "Партнёр (создатель слота): " + creatorLabel)
                                .build());

                        sendNotification(telegramClient, creator.getTelegramId(),
                                "Новая заявка к вашему слоту.\n"
                                        + "Старт: " + dtStr + "\n"
                                        + "Язык: " + state.language
                                        + levelInfo + "\n"
                                        + "Партнёр: " + joinerLabel);
                        telegramClient.execute(SendMessage.builder()
                                .chatId(creator.getTelegramId())
                                .text("Подтвердить заявку:")
                                .replyMarkup(requestKeyboard(request.getId()))
                                .build());
                    } else {
                        boolean solo = state.candidateUserId.equals(state.interviewerUserId);
                        if (solo) {
                            interviewRequestService.createRequest(
                                    state.candidateUserId,
                                    state.interviewerUserId,
                                    state.language,
                                    state.format,
                                    state.dateTime,
                                    state.durationMinutes);
                            telegramClient.execute(SendMessage.builder().chatId(chatId)
                                    .text("Открытая заявка создана. Когда кто-то отправит запрос на этот слот — вы получите подтверждение.")
                                    .build());
                        } else {
                            InterviewRequest request = interviewRequestService.createRequest(
                                    state.candidateUserId,
                                    state.interviewerUserId,
                                    state.language,
                                    state.format,
                                    state.dateTime,
                                    state.durationMinutes);
                            User candidate = userService.getUserById(state.candidateUserId);
                            User interviewer = userService.getUserById(state.interviewerUserId);
                            String candidateLabel = candidate.getUsername() != null
                                    ? "@" + candidate.getUsername()
                                    : "пользователь #" + candidate.getId();
                            telegramClient.execute(SendMessage.builder().chatId(chatId)
                                    .text("Заявка отправлена интервьюеру. Ожидайте ответа.")
                                    .build());
                            telegramClient.execute(SendMessage.builder()
                                    .chatId(interviewer.getTelegramId())
                                    .text("Новая заявка на собеседование от " + candidateLabel + ":\n"
                                            + "Язык: " + state.language + "\n"
                                            + "Дата/время: " + DT_FORMAT.format(state.dateTime) + "\n"
                                            + "Длительность: " + state.durationMinutes + " мин")
                                    .replyMarkup(requestKeyboard(request.getId()))
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
                            .text("Нельзя записаться на время в прошлом. Выберите другое время.").build());
                    state.step = CreateInterviewState.Step.VIEW_SLOT_DATES;
                    List<AvailableSlotDto> slots = state.availableSlots != null ? state.availableSlots : List.of();
                    telegramClient.execute(SendMessage.builder()
                            .chatId(chatId)
                            .text("Выберите день в календаре:")
                            .replyMarkup(slotCalendarKeyboard(slots, state.slotCalendarYear, state.slotCalendarMonth))
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

    private static InlineKeyboardMarkup slotCalendarKeyboard(List<AvailableSlotDto> slots, int year, int month) {
        if (slots == null) slots = List.of();
        Set<LocalDate> datesWithSlots = slots.stream().map(s -> s.dateTime().toLocalDate()).collect(Collectors.toSet());
        LocalDate today = LocalDate.now();
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
                label = day + "✓";
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
            LocalDate date, Map<Integer, CalendarRoleType> busyHours, Set<Integer> availableHours, LocalDateTime now) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();
        for (int hour = TIME_PICKER_START_HOUR; hour < TIME_PICKER_END_HOUR; hour++) {
            LocalDateTime slotAt = date.atTime(hour, 0);
            boolean past = slotAt.isBefore(now);
            CalendarRoleType busy = busyHours != null ? busyHours.get(hour) : null;
            String timeStr = LocalTime.of(hour, 0).format(TIME_LABEL);
            String label;
            String callback;
            if (past) {
                label = timeStr;
                callback = "ci:timepast";
            } else if (busy == CalendarRoleType.CANDIDATE) {
                label = timeStr + " " + ROLE_CANDIDATE;
                callback = "ci:noop";
            } else if (busy == CalendarRoleType.INTERVIEWER) {
                label = timeStr + " " + ROLE_INTERVIEWER;
                callback = "ci:noop";
            } else if (availableHours != null && availableHours.contains(hour)) {
                label = timeStr + " ✓";
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

    private void handleInterviewRequestCallback(
            Long chatId,
            String data,
            org.telegram.telegrambots.meta.api.objects.User fromUser,
            TelegramClient telegramClient) throws TelegramApiException {
        // ir:accept:<id> / ir:decline:<id>
        if (fromUser == null) {
            telegramClient.execute(SendMessage.builder().chatId(chatId).text("Не удалось определить пользователя.").build());
            return;
        }
        long actorTelegramId = fromUser.getId();
        String[] parts = data.split(":", 3);
        if (parts.length != 3) {
            telegramClient.execute(SendMessage.builder().chatId(chatId).text("Некорректная заявка.").build());
            return;
        }
        String action = parts[1];
        Long requestId;
        try {
            requestId = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            telegramClient.execute(SendMessage.builder().chatId(chatId).text("Некорректная заявка.").build());
            return;
        }

        if (action.equals("decline")) {
            try {
                var req = interviewRequestService.decline(requestId, actorTelegramId, LocalDateTime.now(clock));
                telegramClient.execute(SendMessage.builder().chatId(chatId).text("Ок, отклонил.").build());
                sendMainMenu(chatId, telegramClient);
                User candidate = userService.getUserById(req.getCandidate().getId());
                telegramClient.execute(SendMessage.builder()
                        .chatId(candidate.getTelegramId())
                        .text("Партнёр отклонил запрос на собеседование.")
                        .replyMarkup(ChatMenuKeyboardBuilder.buildPersistentKeyboard())
                        .build());
            } catch (InterviewRequestForbiddenException e) {
                telegramClient.execute(SendMessage.builder().chatId(chatId)
                        .text("Отклонить заявку может только приглашённый интервьюер.")
                        .replyMarkup(ChatMenuKeyboardBuilder.buildPersistentKeyboard())
                        .build());
            } catch (IllegalArgumentException e) {
                telegramClient.execute(SendMessage.builder().chatId(chatId)
                        .text("Заявка не найдена или уже обработана.")
                        .replyMarkup(ChatMenuKeyboardBuilder.buildPersistentKeyboard())
                        .build());
            }
            return;
        }
        if (action.equals("accept")) {
            InterviewRequest req;
            try {
                req = interviewRequestService.accept(requestId, actorTelegramId, LocalDateTime.now(clock));
            } catch (InterviewRequestForbiddenException e) {
                telegramClient.execute(SendMessage.builder().chatId(chatId)
                        .text("Принять заявку может только приглашённый интервьюер.")
                        .replyMarkup(ChatMenuKeyboardBuilder.buildPersistentKeyboard())
                        .build());
                return;
            } catch (IllegalArgumentException e) {
                telegramClient.execute(SendMessage.builder().chatId(chatId)
                        .text("Заявка не найдена или уже обработана.")
                        .replyMarkup(ChatMenuKeyboardBuilder.buildPersistentKeyboard())
                        .build());
                return;
            }
            try {
                Interview created = interviewService.createInterview(
                        req.getCandidate().getId(),
                        req.getInterviewer().getId(),
                        req.getLanguage(),
                        null,
                        req.getFormat(),
                        req.getDateTime(),
                        req.getDurationMinutes(),
                        true
                );
                Interview forVideo = interviewService.getInterviewWithParticipants(created.getId());
                String meetingLinkBlock = videoMeetingBlock(forVideo);
                telegramClient.execute(SendMessage.builder().chatId(chatId).text("Принято. Собеседование создано." + meetingLinkBlock).build());
                sendMainMenu(chatId, telegramClient);
                User candidate = userService.getUserById(req.getCandidate().getId());
                telegramClient.execute(SendMessage.builder()
                        .chatId(candidate.getTelegramId())
                        .text("Партнёр принял запрос. Собеседование создано на " + DT_FORMAT.format(req.getDateTime()) + "." + meetingLinkBlock)
                        .replyMarkup(ChatMenuKeyboardBuilder.buildPersistentKeyboard())
                        .build());
            } catch (InterviewConflictException e) {
                telegramClient.execute(SendMessage.builder().chatId(chatId).text("Не могу принять: конфликт по времени.").build());
                sendMainMenu(chatId, telegramClient);
            } catch (IllegalArgumentException e) {
                telegramClient.execute(SendMessage.builder().chatId(chatId)
                        .text("Нельзя создать собеседование: указанное время уже в прошлом.")
                        .build());
                sendMainMenu(chatId, telegramClient);
            }
        }
    }

    private enum CalendarRoleType {
        CANDIDATE,
        INTERVIEWER
    }

    private static final String ROLE_CANDIDATE = "🧑‍💻";
    private static final String ROLE_INTERVIEWER = "🧑‍🏫";
    private static final String ROLE_CANDIDATE_CALENDAR = ROLE_CANDIDATE;
    private static final String ROLE_INTERVIEWER_CALENDAR = ROLE_INTERVIEWER;
    private static final String CALENDAR_LABEL_PAD = "\u00A0";

    /**
     * Возвращает Map<hour, role> для занятых слотов пользователя в указанный день.
     */
    private Map<Integer, CalendarRoleType> buildBusyHoursForDate(Long userId, LocalDate date) {
        if (userId == null) return Map.of();
        List<InterviewRequest> requests = interviewRequestService.getUserRequests(userId, null);
        Map<Integer, CalendarRoleType> busy = new java.util.HashMap<>();
        for (InterviewRequest i : requests) {
            if (i.getStatus() == InterviewRequestStatus.DECLINED) {
                continue;
            }
            if (i.getDateTime() == null || !i.getDateTime().toLocalDate().equals(date)) {
                continue;
            }
            busy.put(i.getDateTime().getHour(), resolveUserRole(i, userId));
        }
        return busy;
    }

    private void handleInterviewCalendarCallback(Long chatId, Integer messageId, String data, TelegramClient telegramClient) throws TelegramApiException {
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

        if (data.equals("ic:menu")) {
            state.filter = null;
            state.selectedDate = null;
            showScheduleFilterMenu(chatId, messageId, telegramClient);
            return;
        }

        if (data.startsWith("ic:filter:")) {
            String filterStr = data.substring("ic:filter:".length());
            try {
                state.filter = InterviewCalendarState.ScheduleFilter.valueOf(filterStr);
            } catch (IllegalArgumentException e) {
                return;
            }
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

        if (data.startsWith("ic:day:")) {
            String dateStr = data.substring("ic:day:".length());
            LocalDate day = LocalDate.parse(dateStr);
            state.selectedDate = day;

            List<InterviewRequest> scheduled = interviewRequestService.getUserRequests(state.userId, null);
            List<InterviewRequest> forDay = scheduled.stream()
                    .filter(i -> i.getDateTime().toLocalDate().equals(day))
                    .filter(i -> scheduleFilterMatches(i, state.filter))
                    .sorted(java.util.Comparator.comparing(InterviewRequest::getDateTime))
                    .toList();
            Map<Integer, EnumSet<CalendarRoleType>> hourRoles = new java.util.HashMap<>();
            for (InterviewRequest i : forDay) {
                int hour = i.getDateTime().getHour();
                EnumSet<CalendarRoleType> roles = hourRoles.computeIfAbsent(hour, h -> EnumSet.noneOf(CalendarRoleType.class));
                roles.add(resolveUserRole(i, state.userId));
            }

            java.time.format.DateTimeFormatter df = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy");
            String sectionTitle = scheduleFilterSectionName(state.filter);
            String legend = hourRoles.isEmpty() ? ""
                    : (state.filter == InterviewCalendarState.ScheduleFilter.PENDING
                        ? "\n\n" + ROLE_INTERVIEWER + " — открытый слот, ждёте партнёра   " + ROLE_CANDIDATE + " — открытый слот (старые записи)"
                        : "\n\n" + ROLE_CANDIDATE + " — 1-я половина часа: вы «кандидат»   " + ROLE_INTERVIEWER + " — 1-я половина: вы «интервьюер»");
            String title = sectionTitle + " — " + day.format(df) + " (" + formatDayOfWeekRu(day.getDayOfWeek()) + ")" + legend;

            InlineKeyboardMarkup keyboard = timePickerKeyboard(day, hourRoles);

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

            List<InterviewRequest> scheduled = interviewRequestService.getUserRequests(state.userId, null);
            List<InterviewRequest> bookedList = scheduled.stream()
                    .filter(i -> i.getDateTime().toLocalDate().equals(day))
                    .filter(i -> i.getDateTime().getHour() == hour)
                    .filter(i -> scheduleFilterMatches(i, state.filter))
                    .sorted(java.util.Comparator.comparing(InterviewRequest::getDateTime))
                    .toList();

            String text;
            InlineKeyboardMarkup markup;
            if (bookedList.isEmpty()) {
                text = "На это время нет записей в выбранном разделе.";
                markup = timeBackKeyboard(day);
            } else {
                java.time.format.DateTimeFormatter timeFmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm");
                StringBuilder sb = new StringBuilder();
                for (InterviewRequest booked : bookedList) {
                    String time = booked.getDateTime().format(timeFmt);
                    if (sb.length() > 0) sb.append("\n\n");
                    if (state.filter == InterviewCalendarState.ScheduleFilter.PENDING) {
                        sb.append("заявка ожидает решения, ").append(booked.getLanguage())
                                .append(", ").append(booked.getDurationMinutes()).append(" мин, старт ").append(time);
                    } else {
                        boolean isCandidate = resolveUserRole(booked, state.userId) == CalendarRoleType.CANDIDATE;
                        String youAction = isCandidate
                                ? "1-я половина: вы «кандидат»"
                                : "1-я половина: вы «интервьюер»";
                        User partner = isCandidate ? booked.getInterviewer() : booked.getCandidate();
                        String partnerLabel = (partner != null && partner.getUsername() != null)
                                ? "@" + partner.getUsername()
                                : (partner != null ? "пользователь #" + partner.getId() : "—");
                        sb.append(youAction).append(", партнёр: ").append(partnerLabel)
                                .append("\n").append(booked.getLanguage())
                                .append(", ").append(booked.getFormat())
                                .append(", ").append(booked.getDurationMinutes()).append(" мин, старт ").append(time);
                    }
                }
                text = sb.toString();
                markup = timeBackKeyboard(day);
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
        int year = state.calendarYear;
        int month = state.calendarMonth;
        Long userId = state.userId;
        List<InterviewRequest> scheduled = interviewRequestService.getUserRequests(userId, null);
        InlineKeyboardMarkup keyboard = calendarMonthKeyboard(year, month, userId, scheduled, state.filter);

        String sectionTitle = scheduleFilterSectionName(state.filter);
        String text = sectionTitle + " — " + MONTH_NAMES_RU[month - 1] + " " + year + ".\nВыберите день.";

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

    private InlineKeyboardMarkup dayBackKeyboard() {
        return InlineKeyboardMarkup.builder()
                .keyboard(List.of(
                        new InlineKeyboardRow(
                                InlineKeyboardButton.builder().text("⬅️ Календарь").callbackData("ic:back").build()
                        )
                ))
                .build();
    }

    private InlineKeyboardMarkup calendarMonthKeyboard(int year, int month, Long userId, List<InterviewRequest> scheduled, InterviewCalendarState.ScheduleFilter filter) {
        YearMonth ym = YearMonth.of(year, month);

        Map<LocalDate, EnumSet<CalendarRoleType>> rolesByDate = new java.util.HashMap<>();
        for (InterviewRequest i : scheduled) {
            if (i.getDateTime() == null) continue;
            if (!scheduleFilterMatches(i, filter)) continue;
            LocalDate d = i.getDateTime().toLocalDate();
            if (d.getYear() != year || d.getMonthValue() != month) continue;

            rolesByDate.computeIfAbsent(d, k -> EnumSet.noneOf(CalendarRoleType.class));
            rolesByDate.get(d).add(resolveUserRole(i, userId));
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
            EnumSet<CalendarRoleType> roles = rolesByDate.getOrDefault(date, EnumSet.noneOf(CalendarRoleType.class));

            String label = buildDayLabelForCalendar(day, roles, filter);
            week.add(InlineKeyboardButton.builder().text(label).callbackData("ic:day:" + date).build());
            if (week.size() == 7) {
                rows.add(new InlineKeyboardRow(week));
                week = new ArrayList<>();
            }
        }

        if (!week.isEmpty()) {
            while (week.size() < 7) week.add(InlineKeyboardButton.builder().text(" ").callbackData("ic:noop").build());
            rows.add(new InlineKeyboardRow(week));
        }

        // Две отдельные кнопки внизу дают календарю больше доступной ширины на узких экранах.
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("← К разделам").callbackData("ic:menu").build()
        ));
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("Закрыть").callbackData("ic:close").build()
        ));

        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private static String buildDayLabelForCalendar(int day, EnumSet<CalendarRoleType> roles, InterviewCalendarState.ScheduleFilter filter) {
        if (roles == null || roles.isEmpty()) return paddedCalendarLabel(String.valueOf(day));
        // Для календаря используем максимально компактные иконки, чтобы не было "..."
        // на двухзначных датах в узкой 7-колоночной сетке Telegram.
        if (filter == InterviewCalendarState.ScheduleFilter.PENDING) {
            if (roles.contains(CalendarRoleType.INTERVIEWER)) return paddedCalendarLabel(day + ROLE_INTERVIEWER_CALENDAR);
            if (roles.contains(CalendarRoleType.CANDIDATE)) return paddedCalendarLabel(day + ROLE_CANDIDATE_CALENDAR);
            return paddedCalendarLabel(String.valueOf(day));
        }
        if (roles.contains(CalendarRoleType.INTERVIEWER)) return paddedCalendarLabel(day + ROLE_INTERVIEWER_CALENDAR);
        if (roles.contains(CalendarRoleType.CANDIDATE)) return paddedCalendarLabel(day + ROLE_CANDIDATE_CALENDAR);
        return paddedCalendarLabel(String.valueOf(day));
    }

    private static String paddedCalendarLabel(String label) {
        return CALENDAR_LABEL_PAD + label + CALENDAR_LABEL_PAD;
    }

    private InlineKeyboardMarkup timePickerKeyboard(LocalDate date, Map<Integer, EnumSet<CalendarRoleType>> hourRoles) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();

        for (int hour = TIME_PICKER_START_HOUR; hour < TIME_PICKER_END_HOUR; hour++) {
            EnumSet<CalendarRoleType> roles = hourRoles != null
                    ? hourRoles.getOrDefault(hour, EnumSet.noneOf(CalendarRoleType.class))
                    : EnumSet.noneOf(CalendarRoleType.class);
            String label = buildHourLabel(hour, roles);
            row.add(InlineKeyboardButton.builder()
                    .text(label)
                    .callbackData("ic:time:" + date + ":" + hour)
                    .build());
            if (row.size() == 4) {
                rows.add(new InlineKeyboardRow(row));
                row = new ArrayList<>();
            }
        }
        if (!row.isEmpty()) rows.add(new InlineKeyboardRow(row));

        rows.add(new InlineKeyboardRow(InlineKeyboardButton.builder()
                .text("⬅️ Другой день")
                .callbackData("ic:back")
                .build()));

        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private static String buildHourLabel(int hour, EnumSet<CalendarRoleType> roles) {
        String time = LocalTime.of(hour, 0).format(TIME_LABEL);
        if (roles == null || roles.isEmpty()) {
            return time;
        }
        StringBuilder sb = new StringBuilder(time);
        if (roles.contains(CalendarRoleType.CANDIDATE)) sb.append(" ").append(ROLE_CANDIDATE);
        if (roles.contains(CalendarRoleType.INTERVIEWER)) sb.append(" ").append(ROLE_INTERVIEWER);
        return sb.toString();
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
                stateService.startInterviewCalendar(chatId, user.getId());
                showScheduleFilterMenu(chatId, null, telegramClient);
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
            state.joinInterviewId = null;
            state.interviewerUserId = null;
            state.dateTime = null;
            state.durationMinutes = null;
            userService.updateUserLanguage(state.candidateUserId, state.language);

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
            state.joinInterviewId = slot.interviewId();
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

            if (!"yes".equals(action) || state.joinInterviewId == null || state.dateTime == null || state.durationMinutes == null) {
                return;
            }

            if (hasScheduledConflictAtSameTime(state.candidateUserId, state.joinInterviewId, state.dateTime, state.durationMinutes)) {
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text("На этот временной слот у вас уже есть согласованное собеседование. Выберите другое время.")
                        .replyMarkup(availableSlotsKeyboard(state.availableSlots != null ? state.availableSlots : List.of()))
                        .build());
                return;
            }

            try {
                InterviewRequest request = interviewRequestService.createRequest(
                        state.candidateUserId,
                        state.interviewerUserId,
                        state.language,
                        InterviewFormat.TECHNICAL,
                        state.dateTime,
                        state.durationMinutes
                );
                User candidate = userService.getUserById(state.candidateUserId);
                User interviewer = userService.getUserById(state.interviewerUserId);
                String candidateLabel = candidate.getUsername() != null
                        ? "@" + candidate.getUsername()
                        : "пользователь #" + candidate.getId();
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text("Заявка отправлена интервьюеру. Ожидайте ответа.\n"
                                + "Дата/время: " + DT_FORMAT.format(state.dateTime) + "\n"
                                + "Язык: " + state.language)
                        .build());
                telegramClient.execute(SendMessage.builder()
                        .chatId(interviewer.getTelegramId())
                        .text("Новая заявка на собеседование от " + candidateLabel + ":\n"
                                + "Язык: " + state.language + "\n"
                                + "Дата/время: " + DT_FORMAT.format(state.dateTime) + "\n"
                                + "Длительность: " + state.durationMinutes + " мин")
                        .replyMarkup(requestKeyboard(request.getId()))
                        .build());
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
        var csharp = InlineKeyboardButton.builder().text("C#").callbackData("ci:lang:CSHARP").build();
        var python = InlineKeyboardButton.builder().text("Python").callbackData("ci:lang:PYTHON").build();
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
        var cancel = InlineKeyboardButton.builder().text("Отмена").callbackData("ci:cancel").build();
        return InlineKeyboardMarkup.builder().keyboard(List.of(
                new InlineKeyboardRow(java, csharp),
                new InlineKeyboardRow(python, algorithms, productManager, js),
                new InlineKeyboardRow(kotlin, swift),
                new InlineKeyboardRow(go, qa),
                new InlineKeyboardRow(data, ba),
                new InlineKeyboardRow(sa),
                new InlineKeyboardRow(cancel)
        )).build();
    }

    private static InlineKeyboardMarkup availableSlotsLanguageKeyboard() {
        var java = InlineKeyboardButton.builder().text("Java").callbackData("as:lang:JAVA").build();
        var csharp = InlineKeyboardButton.builder().text("C#").callbackData("as:lang:CSHARP").build();
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
        var cancel = InlineKeyboardButton.builder().text("Отмена").callbackData("as:cancel").build();
        return InlineKeyboardMarkup.builder().keyboard(List.of(
                new InlineKeyboardRow(java, csharp),
                new InlineKeyboardRow(python, algorithms, productManager, js),
                new InlineKeyboardRow(kotlin, swift),
                new InlineKeyboardRow(go, qa),
                new InlineKeyboardRow(data, ba),
                new InlineKeyboardRow(sa),
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

    private boolean hasScheduledConflictAtSameTime(Long userId, Long excludeInterviewId, LocalDateTime start, int durationMinutes) {
        if (userId == null || start == null) return false;
        LocalDateTime end = start.plusMinutes(durationMinutes);
        return interviewRequestService.getUserRequests(userId, null).stream()
                .filter(r -> r.getStatus() != InterviewRequestStatus.DECLINED)
                .filter(r -> excludeInterviewId == null || !excludeInterviewId.equals(r.getId()))
                .anyMatch(r -> {
                    LocalDateTime iStart = r.getDateTime();
                    LocalDateTime iEnd = iStart.plusMinutes(r.getDurationMinutes());
                    return iStart.isBefore(end) && start.isBefore(iEnd);
                });
    }

    private static InlineKeyboardMarkup requestKeyboard(Long requestId) {
        var accept = InlineKeyboardButton.builder().text("Принять").callbackData("ir:accept:" + requestId).build();
        var decline = InlineKeyboardButton.builder().text("Отклонить").callbackData("ir:decline:" + requestId).build();
        List<InlineKeyboardRow> rows = List.of(new InlineKeyboardRow(accept, decline));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private static final String[] DAY_NAMES_RU = new String[]{"Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"};
    private static final String[] MONTH_NAMES_RU = new String[]{"Янв", "Фев", "Мар", "Апр", "Май", "Июн", "Июл", "Авг", "Сен", "Окт", "Ноя", "Дек"};

    private static String formatDayOfWeekRu(DayOfWeek d) {
        return DAY_NAMES_RU[d.getValue() - 1];
    }


    /** Проверяет, соответствует ли интервью выбранному фильтру расписания. */
    private static boolean scheduleFilterMatches(InterviewRequest i, InterviewCalendarState.ScheduleFilter filter) {
        if (filter == null) return true;
        return switch (filter) {
            case PENDING -> i.getStatus() == InterviewRequestStatus.PENDING;
            case CONFIRMED -> i.getStatus() == InterviewRequestStatus.ACCEPTED;
        };
    }

    private static CalendarRoleType resolveUserRole(InterviewRequest request, Long userId) {
        boolean isInterviewer = request.getInterviewer() != null
                && request.getInterviewer().getId() != null
                && request.getInterviewer().getId().equals(userId);
        return isInterviewer ? CalendarRoleType.INTERVIEWER : CalendarRoleType.CANDIDATE;
    }

    /** Название раздела расписания для отображения пользователю. */
    private static String scheduleFilterSectionName(InterviewCalendarState.ScheduleFilter filter) {
        if (filter == null) return "Расписание";
        return switch (filter) {
            case PENDING -> "Заявки на собеседования";
            case CONFIRMED -> "Согласованные собеседования";
        };
    }

    /** Показывает меню выбора раздела расписания. */
    private void showScheduleFilterMenu(Long chatId, Integer messageId, TelegramClient telegramClient) throws TelegramApiException {
        String text = "Расписание — выберите раздел:";
        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder().keyboard(List.of(
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("Заявки на собеседования").callbackData("ic:filter:PENDING").build()
                ),
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("Согласованные собеседования").callbackData("ic:filter:CONFIRMED").build()
                ),
                new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("Закрыть").callbackData("ic:close").build()
                )
        )).build();
        if (messageId != null) {
            try {
                telegramClient.execute(EditMessageText.builder()
                        .chatId(chatId)
                        .messageId(messageId)
                        .text(text)
                        .replyMarkup(keyboard)
                        .build());
                return;
            } catch (TelegramApiException ignored) {
            }
        }
        telegramClient.execute(SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .replyMarkup(keyboard)
                .build());
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
        } catch (TelegramApiException ignored) {
        }
    }
}
