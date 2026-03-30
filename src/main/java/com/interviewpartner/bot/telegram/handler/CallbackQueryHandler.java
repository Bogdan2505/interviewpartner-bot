package com.interviewpartner.bot.telegram.handler;

import com.interviewpartner.bot.exception.InterviewConflictException;
import com.interviewpartner.bot.exception.ScheduleOverlapException;
import com.interviewpartner.bot.exception.UserNotFoundException;
import com.interviewpartner.bot.model.CandidateSlot;
import com.interviewpartner.bot.model.Interview;
import com.interviewpartner.bot.model.InterviewFormat;
import com.interviewpartner.bot.model.InterviewRequest;
import com.interviewpartner.bot.model.InterviewStatus;
import com.interviewpartner.bot.model.Language;
import com.interviewpartner.bot.model.Level;
import com.interviewpartner.bot.model.Schedule;
import com.interviewpartner.bot.model.User;
import com.interviewpartner.bot.service.CandidateSlotService;
import com.interviewpartner.bot.service.InterviewService;
import com.interviewpartner.bot.service.ScheduleService;
import com.interviewpartner.bot.service.UserService;
import com.interviewpartner.bot.service.dto.AvailableSlotDto;
import com.interviewpartner.bot.telegram.flow.CandidateSlotState;
import com.interviewpartner.bot.telegram.flow.ConversationStateService;
import com.interviewpartner.bot.telegram.flow.CreateInterviewState;
import com.interviewpartner.bot.telegram.flow.FindPartnerState;
import com.interviewpartner.bot.telegram.flow.InterviewCalendarState;
import com.interviewpartner.bot.telegram.flow.ScheduleState;
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
    private final ScheduleService scheduleService;
    private final CandidateSlotService candidateSlotService;
    private final UserService userService;
    private final InterviewRequestService interviewRequestService;

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
            if (safeData.startsWith("fp:")) {
                handleFindPartnerCallback(chatId, safeData, telegramClient);
                return;
            }
            if (safeData.startsWith("ic:")) {
                Integer messageId = callback.getMessage() != null ? callback.getMessage().getMessageId() : null;
                handleInterviewCalendarCallback(chatId, messageId, safeData, telegramClient);
                return;
            }
            if (safeData.startsWith("sc:")) {
                Integer messageId = callback.getMessage() != null ? callback.getMessage().getMessageId() : null;
                handleScheduleCallback(chatId, messageId, safeData, telegramClient);
                return;
            }
            if (safeData.startsWith("cs:")) {
                Integer messageId = callback.getMessage() != null ? callback.getMessage().getMessageId() : null;
                handleCandidateSlotCallback(chatId, messageId, safeData, telegramClient);
                return;
            }
            if (safeData.startsWith("ir:")) {
                handleInterviewRequestCallback(chatId, safeData, telegramClient);
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
            Long myUserId = state.asCandidate ? state.candidateUserId : state.interviewerUserId;
            if (myUserId != null) userService.updateUserLanguage(myUserId, state.language);
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
            Long myUserId = state.asCandidate ? state.candidateUserId : state.interviewerUserId;
            if (myUserId != null && state.level != null) userService.updateUserLevel(myUserId, state.level);
            List<AvailableSlotDto> slots = state.asCandidate
                    ? interviewService.getAvailableSlotsAsCandidate(state.candidateUserId, state.language, state.level, 14)
                    : interviewService.getAvailableSlotsAsInterviewer(state.interviewerUserId, state.language, state.level, 14);
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

            Long myUserId = state.asCandidate ? state.candidateUserId : state.interviewerUserId;
            Map<Integer, CalendarRoleType> busyHours = buildBusyHoursForDate(myUserId, state.selectedSlotDate, state.asCandidate);

            // Часы, в которые есть доступный партнёр
            Set<Integer> availableHours = (state.availableSlots == null ? List.<AvailableSlotDto>of() : state.availableSlots)
                    .stream()
                    .filter(s -> s.dateTime().toLocalDate().equals(state.selectedSlotDate))
                    .map(s -> s.dateTime().getHour())
                    .collect(java.util.stream.Collectors.toSet());

            state.step = CreateInterviewState.Step.VIEW_SLOTS;

            StringBuilder legend = new StringBuilder();
            if (!availableHours.isEmpty()) legend.append("\n✓ — есть партнёр для этого времени");
            if (!busyHours.isEmpty()) {
                legend.append("\n").append(ROLE_CANDIDATE).append(" — уже есть собеседование (кандидат)")
                        .append("  ").append(ROLE_INTERVIEWER).append(" — вы проводите собеседование");
            }

            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(dayLabel + (legend.length() > 0 ? legend.toString() : ""))
                    .replyMarkup(createInterviewTimePickerKeyboard(state.selectedSlotDate, busyHours, availableHours, LocalDateTime.now()))
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
            if (chosen.isBefore(LocalDateTime.now())) {
                Long myUserId = state.asCandidate ? state.candidateUserId : state.interviewerUserId;
                Map<Integer, CalendarRoleType> busyHours = buildBusyHoursForDate(myUserId, date, state.asCandidate);
                Set<Integer> availableHours = (state.availableSlots == null ? List.<AvailableSlotDto>of() : state.availableSlots)
                        .stream()
                        .filter(s -> s.dateTime().toLocalDate().equals(date))
                        .map(s -> s.dateTime().getHour())
                        .collect(java.util.stream.Collectors.toSet());
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text("Это время уже прошло. Выберите другое.")
                        .replyMarkup(createInterviewTimePickerKeyboard(date, busyHours, availableHours, LocalDateTime.now()))
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
                if (state.asCandidate) {
                    state.interviewerUserId = matchedSlot.partnerUserId();
                } else {
                    state.candidateUserId = matchedSlot.partnerUserId();
                }
                state.step = CreateInterviewState.Step.CONFIRM;
                String partnerLabel = matchedSlot.partnerLabel()
                        + (matchedSlot.partnerLevel() != null ? " [" + levelLabel(matchedSlot.partnerLevel()) + "]" : "");
                boolean joining = state.joinInterviewId != null;
                String summary = (joining ? "Записаться на собеседование?\n" : "Подтвердить создание?\n")
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
                // Нет готового слота — предлагаем создать без партнёра
                if (state.asCandidate) {
                    state.interviewerUserId = state.candidateUserId;
                } else {
                    state.candidateUserId = state.interviewerUserId;
                }
                state.step = CreateInterviewState.Step.CONFIRM;
                String summary = "Партнёр пока не найден. Создать слот на "
                        + DT_FORMAT.format(state.dateTime) + " (" + state.language + ")"
                        + (state.level != null ? " [" + levelLabel(state.level) + "]" : "") + "?\n"
                        + "Партнёр будет подобран позже.";
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
            if (slot.dateTime().isBefore(LocalDateTime.now())) {
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
            if (state.asCandidate) {
                state.interviewerUserId = slot.partnerUserId();
            } else {
                state.candidateUserId = slot.partnerUserId();
            }
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
            if (state.asCandidate) {
                if (payload.equals("self")) {
                    state.interviewerUserId = state.candidateUserId;
                } else {
                    state.interviewerUserId = Long.parseLong(payload);
                }
            } else {
                state.candidateUserId = payload.equals("self") ? state.interviewerUserId : Long.parseLong(payload);
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
                        // Присоединяемся к существующему solo-слоту
                        Long myId = state.asCandidate ? state.candidateUserId : state.interviewerUserId;
                        Long creatorId = state.asCandidate ? state.interviewerUserId : state.candidateUserId;
                        Interview joined = interviewService.joinInterview(state.joinInterviewId, myId, state.asCandidate);
                        Interview forVideo = interviewService.getInterviewWithParticipants(joined.getId());

                        User joiner = userService.getUserById(myId);
                        User creator = userService.getUserById(creatorId);

                        String joinerRole = state.asCandidate ? "кандидат" : "интервьюер";
                        String creatorRole = state.asCandidate ? "интервьюер" : "кандидат";
                        String creatorLabel = creator.getUsername() != null ? "@" + creator.getUsername() : "пользователь #" + creatorId;
                        String joinerLabel = joiner.getUsername() != null ? "@" + joiner.getUsername() : "пользователь #" + myId;
                        String dtStr = DT_FORMAT.format(state.dateTime);

                        String levelInfo = state.level != null ? "\nГрейд: " + levelLabel(state.level) : "";
                        String meetingLinkBlock = videoMeetingBlock(forVideo);

                        // Уведомление присоединившемуся
                        telegramClient.execute(SendMessage.builder()
                                .chatId(chatId)
                                .text("Вы записаны на собеседование!\n"
                                        + "Дата/время: " + dtStr + "\n"
                                        + "Язык: " + state.language
                                        + levelInfo + "\n"
                                        + "Партнёр: " + creatorLabel + "\n"
                                        + "Ваша роль: " + joinerRole
                                        + meetingLinkBlock)
                                .build());

                        // Уведомление создателю слота
                        sendNotification(telegramClient, creator.getTelegramId(),
                                "Найден партнёр для вашего слота!\n"
                                        + "Дата/время: " + dtStr + "\n"
                                        + "Язык: " + state.language
                                        + levelInfo + "\n"
                                        + "Партнёр: " + joinerLabel + "\n"
                                        + "Ваша роль: " + creatorRole
                                        + meetingLinkBlock);
                    } else {
                        interviewService.createInterview(
                                state.candidateUserId,
                                state.interviewerUserId,
                                state.language,
                                state.level,
                                state.format,
                                state.dateTime,
                                state.durationMinutes,
                                state.asCandidate);
                        telegramClient.execute(SendMessage.builder().chatId(chatId).text("Слот создан. Как только найдётся партнёр — вы получите уведомление.").build());
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
                label = day + " ✓";
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

    private static InlineKeyboardMarkup slotsKeyboard(List<AvailableSlotDto> slots) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            AvailableSlotDto s = slots.get(i);
            String label = SLOT_LABEL.format(s.dateTime()) + " — " + s.partnerLabel();
            if (label.length() > 64) label = label.substring(0, 61) + "...";
            rows.add(new InlineKeyboardRow(
                    InlineKeyboardButton.builder().text(label).callbackData("ci:slot:" + i).build()));
        }
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("Ввести дату вручную").callbackData("ci:slot:manual").build()));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private static InlineKeyboardMarkup formatKeyboard() {
        var tech = InlineKeyboardButton.builder().text("Техническое").callbackData("ci:format:TECHNICAL").build();
        var beh = InlineKeyboardButton.builder().text("Поведенческое").callbackData("ci:format:BEHAVIORAL").build();
        var cancel = InlineKeyboardButton.builder().text("Отмена").callbackData("ci:cancel").build();
        List<InlineKeyboardRow> rows = List.of(
                new InlineKeyboardRow(tech, beh),
                new InlineKeyboardRow(cancel)
        );
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private void handleFindPartnerCallback(Long chatId, String data, TelegramClient telegramClient) throws TelegramApiException {
        if (data.equals("fp:cancel")) {
            stateService.clearFindPartner(chatId);
            telegramClient.execute(SendMessage.builder().chatId(chatId).text("Ок, отменил поиск партнёра.").build());
            sendMainMenu(chatId, telegramClient);
            return;
        }
        FindPartnerState state = stateService.getFindPartner(chatId).orElse(null);
        if (state == null) {
            telegramClient.execute(SendMessage.builder().chatId(chatId).text("Сессия истекла. Начните заново: /find_partner").replyMarkup(ChatMenuKeyboardBuilder.buildPersistentKeyboard()).build());
            return;
        }
        if (data.startsWith("fp:lang:")) {
            state.language = Language.valueOf(data.substring("fp:lang:".length()));
            if (state.requesterUserId != null) userService.updateUserLanguage(state.requesterUserId, state.language);
            state.step = FindPartnerState.Step.DATE_TIME;
            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("Введите дату и время в формате: yyyy-MM-dd HH:mm (например 2026-03-25 19:00)")
                    .build());
            return;
        }
        if (data.startsWith("fp:pick:")) {
            Long partnerUserId = Long.parseLong(data.substring("fp:pick:".length()));
            if (state.language == null || state.dateTime == null) {
                telegramClient.execute(SendMessage.builder().chatId(chatId)
                        .text("Недостаточно данных. Начните заново: /find_partner")
                        .build());
                stateService.clearFindPartner(chatId);
                return;
            }

            InterviewRequest req;
            try {
                req = interviewRequestService.createRequest(
                        state.requesterUserId,
                        partnerUserId,
                        state.language,
                        InterviewFormat.TECHNICAL,
                        state.dateTime,
                        60
                );
            } catch (IllegalArgumentException e) {
                telegramClient.execute(SendMessage.builder().chatId(chatId)
                        .text("Нельзя отправить запрос на время в прошлом. Выберите другое время.")
                        .build());
                return;
            }

            User partner = userService.getUserById(partnerUserId);
            Long partnerChatId = partner.getTelegramId();

            telegramClient.execute(SendMessage.builder()
                    .chatId(partnerChatId)
                    .text("Вам пришёл запрос на собеседование:\n"
                            + "Язык: " + req.getLanguage() + "\n"
                            + "Формат: " + req.getFormat() + "\n"
                            + "Дата/время: " + DT_FORMAT.format(req.getDateTime()) + "\n"
                            + "Длительность: " + req.getDurationMinutes() + " мин.\n\n"
                            + "Принять?")
                    .replyMarkup(requestKeyboard(req.getId()))
                    .build());

            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("Отправил запрос партнёру. Ожидайте ответа.")
                    .build());
            stateService.clearFindPartner(chatId);
            sendMainMenu(chatId, telegramClient);
        }
    }

    private void handleInterviewRequestCallback(Long chatId, String data, TelegramClient telegramClient) throws TelegramApiException {
        // ir:accept:<id> / ir:decline:<id>
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
            var req = interviewRequestService.decline(requestId, LocalDateTime.now());
            telegramClient.execute(SendMessage.builder().chatId(chatId).text("Ок, отклонил.").build());
            sendMainMenu(chatId, telegramClient);
            User candidate = userService.getUserById(req.getCandidate().getId());
            telegramClient.execute(SendMessage.builder()
                    .chatId(candidate.getTelegramId())
                    .text("Партнёр отклонил запрос на собеседование.")
                    .replyMarkup(ChatMenuKeyboardBuilder.buildPersistentKeyboard())
                    .build());
            return;
        }
        if (action.equals("accept")) {
            var req = interviewRequestService.accept(requestId, LocalDateTime.now());
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

    /**
     * Возвращает Map<hour, role> для занятых слотов пользователя в указанный день.
     */
    private Map<Integer, CalendarRoleType> buildBusyHoursForDate(Long userId, LocalDate date, boolean asCandidate) {
        if (userId == null) return Map.of();
        List<com.interviewpartner.bot.model.Interview> scheduled =
                interviewService.getUserInterviews(userId, InterviewStatus.SCHEDULED);
        Map<Integer, CalendarRoleType> busy = new java.util.HashMap<>();
        for (com.interviewpartner.bot.model.Interview i : scheduled) {
            if (i.getDateTime() == null || !i.getDateTime().toLocalDate().equals(date)) continue;
            boolean isSoloInterviewerSlot = i.getCandidate() != null && i.getInterviewer() != null
                    && i.getCandidate().getId() != null
                    && i.getCandidate().getId().equals(i.getInterviewer().getId())
                    && !i.isInitiatorIsCandidate();
            // В потоке "Записаться": не показываем свои solo-"Провести"-слоты как занятые,
            // чтобы можно было видеть и присоединяться к чужим слотам в то же время.
            // В потоке "Провести": показываем все слоты, включая свои solo-слоты.
            if (asCandidate && isSoloInterviewerSlot) {
                continue;
            }
            busy.put(i.getDateTime().getHour(), resolveUserRole(i, userId));
        }
        return busy;
    }

    /**
     * Определяет роль пользователя в интервью.
     * Для solo-слотов (когда оба поля указывают на одного пользователя)
     * используется поле initiatorIsCandidate, сохранённое при создании.
     */
    private static CalendarRoleType resolveUserRole(com.interviewpartner.bot.model.Interview interview, Long userId) {
        boolean isCandidate = interview.getCandidate() != null
                && interview.getCandidate().getId() != null
                && interview.getCandidate().getId().equals(userId);
        boolean isInterviewer = interview.getInterviewer() != null
                && interview.getInterviewer().getId() != null
                && interview.getInterviewer().getId().equals(userId);
        if (isCandidate && isInterviewer) {
            return interview.isInitiatorIsCandidate() ? CalendarRoleType.CANDIDATE : CalendarRoleType.INTERVIEWER;
        }
        if (isInterviewer) return CalendarRoleType.INTERVIEWER;
        return CalendarRoleType.CANDIDATE;
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

            List<Interview> scheduled = interviewService.getUserInterviews(state.userId, InterviewStatus.SCHEDULED);
            List<Interview> forDay = scheduled.stream()
                    .filter(i -> i.getDateTime().toLocalDate().equals(day))
                    .filter(i -> scheduleFilterMatches(i, state.filter))
                    .sorted(java.util.Comparator.comparing(Interview::getDateTime))
                    .toList();
            Map<Integer, EnumSet<CalendarRoleType>> hourRoles = new java.util.HashMap<>();
            for (Interview i : forDay) {
                int hour = i.getDateTime().getHour();
                EnumSet<CalendarRoleType> roles = hourRoles.computeIfAbsent(hour, h -> EnumSet.noneOf(CalendarRoleType.class));
                roles.add(resolveUserRole(i, state.userId));
            }

            java.time.format.DateTimeFormatter df = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy");
            String sectionTitle = scheduleFilterSectionName(state.filter);
            String legend = hourRoles.isEmpty() ? ""
                    : (state.filter == InterviewCalendarState.ScheduleFilter.PENDING
                        ? "\n\n" + ROLE_CANDIDATE + " — жду интервьюера   " + ROLE_INTERVIEWER + " — жду кандидата"
                        : "\n\n" + ROLE_CANDIDATE + " — ты кандидат   " + ROLE_INTERVIEWER + " — ты интервьюер");
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

            List<Interview> scheduled = interviewService.getUserInterviews(state.userId, InterviewStatus.SCHEDULED);
            List<Interview> bookedList = scheduled.stream()
                    .filter(i -> i.getDateTime().toLocalDate().equals(day))
                    .filter(i -> i.getDateTime().getHour() == hour)
                    .filter(i -> scheduleFilterMatches(i, state.filter))
                    .sorted(java.util.Comparator.comparing(Interview::getDateTime))
                    .toList();

            String text;
            InlineKeyboardMarkup markup;
            if (bookedList.isEmpty()) {
                text = "На это время нет записей в выбранном разделе.";
                markup = timeBackKeyboard(day);
            } else {
                java.time.format.DateTimeFormatter timeFmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm");
                StringBuilder sb = new StringBuilder();
                for (Interview booked : bookedList) {
                    String time = booked.getDateTime().format(timeFmt);
                    if (sb.length() > 0) sb.append("\n\n");
                    String levelSuffix = booked.getLevel() != null ? " [" + levelLabel(booked.getLevel()) + "]" : "";
                    if (state.filter == InterviewCalendarState.ScheduleFilter.PENDING) {
                        String waitFor = booked.isInitiatorIsCandidate() ? "жду интервьюера" : "жду кандидата";
                        sb.append(waitFor).append(", ").append(booked.getLanguage())
                                .append(levelSuffix)
                                .append(", ").append(booked.getDuration()).append(" мин в ").append(time);
                    } else {
                        boolean isCandidate = resolveUserRole(booked, state.userId) == CalendarRoleType.CANDIDATE;
                        String youAction = isCandidate ? "ты кандидат" : "ты интервьюер";
                        User partner = isCandidate ? booked.getInterviewer() : booked.getCandidate();
                        String partnerLabel = (partner != null && partner.getUsername() != null)
                                ? "@" + partner.getUsername()
                                : (partner != null ? "пользователь #" + partner.getId() : "—");
                        sb.append(youAction).append(", партнёр: ").append(partnerLabel)
                                .append("\n").append(booked.getLanguage())
                                .append(levelSuffix)
                                .append(", ").append(booked.getFormat())
                                .append(", ").append(booked.getDuration()).append(" мин в ").append(time);
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
        List<Interview> scheduled = interviewService.getUserInterviews(userId, InterviewStatus.SCHEDULED);
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

    private InlineKeyboardMarkup calendarMonthKeyboard(int year, int month, Long userId, List<Interview> scheduled, InterviewCalendarState.ScheduleFilter filter) {
        YearMonth ym = YearMonth.of(year, month);

        Map<LocalDate, EnumSet<CalendarRoleType>> rolesByDate = new java.util.HashMap<>();
        for (Interview i : scheduled) {
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

            String label = buildDayLabelForCalendar(day, roles);
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

        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("← К разделам").callbackData("ic:menu").build(),
                InlineKeyboardButton.builder().text("Закрыть").callbackData("ic:close").build()
        ));

        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private static String buildDayLabelForCalendar(int day, EnumSet<CalendarRoleType> roles) {
        if (roles == null || roles.isEmpty()) return String.valueOf(day);
        StringBuilder sb = new StringBuilder();
        sb.append(day);
        if (roles.contains(CalendarRoleType.CANDIDATE)) sb.append(" ").append(ROLE_CANDIDATE);
        if (roles.contains(CalendarRoleType.INTERVIEWER)) sb.append(" ").append(ROLE_INTERVIEWER);
        return sb.toString();
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
                stateService.startCreateInterview(chatId, user.getId(), true);
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text("Записаться на собеседование: выберите направление.")
                        .replyMarkup(createInterviewLanguageKeyboard())
                        .build());
            }
            case "cmd:find_partner" -> {
                stateService.startCreateInterview(chatId, user.getId(), false);
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text("Провести собеседование: выберите направление.")
                        .replyMarkup(createInterviewLanguageKeyboard())
                        .build());
            }
            case "cmd:schedule" -> {
                stateService.startInterviewCalendar(chatId, user.getId());
                showScheduleFilterMenu(chatId, null, telegramClient);
            }
            case "cmd:help" -> {
                String helpText = """
                        Справка

                        • Записаться на собеседование — вы кандидат, вам подберут интервьюера.
                        • Провести собеседование — вы интервьюер, проводите встречу с кандидатом.
                        • Расписание — календарь ваших запланированных собеседований по ролям.
                        • Помощь — эта подсказка.""";
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text(helpText)
                        .replyMarkup(ChatMenuKeyboardBuilder.buildPersistentKeyboard())
                        .build());
            }
            default -> sendMainMenu(chatId, telegramClient);
        }
    }

    private void sendMainMenu(Long chatId, TelegramClient telegramClient) throws TelegramApiException {
        telegramClient.execute(SendMessage.builder()
                .chatId(chatId)
                .text(MainMenuBuilder.getShortMenuPrompt())
                .replyMarkup(ChatMenuKeyboardBuilder.buildPersistentKeyboard())
                .build());
    }

    private void sendScheduleMenu(Long chatId, Long userId, Language language, TelegramClient telegramClient) throws TelegramApiException {
        List<Schedule> slots = scheduleService.getUserSchedule(userId);
        Language effectiveLanguage = language != null ? language :
                (slots.isEmpty() ? null : slots.get(0).getLanguage());
        if (effectiveLanguage == null) {
            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("Укажите своё направление:")
                    .replyMarkup(AvailabilityCommandHandler.languageKeyboard())
                    .build());
            return;
        }
        telegramClient.execute(SendMessage.builder()
                .chatId(chatId)
                .text(AvailabilityCommandHandler.renderSchedule(slots, effectiveLanguage))
                .replyMarkup(AvailabilityCommandHandler.scheduleMenuKeyboard())
                .build());
    }

    private String buildInterviewsText(Long userId) {
        List<Interview> all = interviewService.getUserInterviews(userId, null);
        var now = LocalDateTime.now();
        var upcoming = all.stream().filter(i -> i.getDateTime().isAfter(now) && i.getStatus() == InterviewStatus.SCHEDULED).toList();
        var past = all.stream().filter(i -> i.getDateTime().isBefore(now) || i.getStatus() != InterviewStatus.SCHEDULED).toList();
        StringBuilder sb = new StringBuilder();
        sb.append("Ваши собеседования\n\n");
        sb.append("Предстоящие:\n");
        if (upcoming.isEmpty()) {
            sb.append("- нет\n");
        } else {
            for (Interview i : upcoming) {
                sb.append("- ").append(DT_FORMAT.format(i.getDateTime()))
                        .append(" • ").append(i.getLanguage())
                        .append(" • ").append(i.getFormat())
                        .append(" • ").append(i.getDuration()).append(" мин")
                        .append("\n");
            }
        }
        sb.append("\nПрошедшие/отменённые:\n");
        if (past.isEmpty()) {
            sb.append("- нет\n");
        } else {
            for (Interview i : past) {
                sb.append("- ").append(DT_FORMAT.format(i.getDateTime()))
                        .append(" • ").append(i.getStatus())
                        .append(" • ").append(i.getLanguage())
                        .append(" • ").append(i.getFormat())
                        .append("\n");
            }
        }
        return sb.toString();
    }

    private static String renderSchedule(List<Schedule> slots) {
        if (slots.isEmpty()) {
            return "Ваше расписание, когда вы готовы провести собеседование, пока пустое.\n\nДобавьте доступность, чтобы вас могли находить партнёры.";
        }
        StringBuilder sb = new StringBuilder("Ваше расписание, когда вы готовы провести собеседование:\n");
        for (Schedule s : slots) {
            sb.append("- ").append(s.getDayOfWeek())
                    .append(" ").append(s.getStartTime())
                    .append("–").append(s.getEndTime())
                    .append(" (id=").append(s.getId()).append(")")
                    .append("\n");
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
        var js = InlineKeyboardButton.builder().text("JavaScript").callbackData("ci:lang:JAVASCRIPT").build();
        var go = InlineKeyboardButton.builder().text("Go").callbackData("ci:lang:GO").build();
        var qa = InlineKeyboardButton.builder().text("QA").callbackData("ci:lang:QA").build();
        var data = InlineKeyboardButton.builder().text("Data Analytics").callbackData("ci:lang:DATA_ANALYTICS").build();
        var ba = InlineKeyboardButton.builder().text("Business Analysis").callbackData("ci:lang:BUSINESS_ANALYSIS").build();
        var sa = InlineKeyboardButton.builder().text("System Analysis").callbackData("ci:lang:SYSTEM_ANALYSIS").build();
        var cancel = InlineKeyboardButton.builder().text("Отмена").callbackData("ci:cancel").build();
        return InlineKeyboardMarkup.builder().keyboard(List.of(
                new InlineKeyboardRow(java, python),
                new InlineKeyboardRow(js, go),
                new InlineKeyboardRow(qa, data),
                new InlineKeyboardRow(ba, sa),
                new InlineKeyboardRow(cancel)
        )).build();
    }

    private static InlineKeyboardMarkup findPartnerLanguageKeyboard() {
        var java = InlineKeyboardButton.builder().text("Java").callbackData("fp:lang:JAVA").build();
        var python = InlineKeyboardButton.builder().text("Python").callbackData("fp:lang:PYTHON").build();
        var js = InlineKeyboardButton.builder().text("JavaScript").callbackData("fp:lang:JAVASCRIPT").build();
        var go = InlineKeyboardButton.builder().text("Go").callbackData("fp:lang:GO").build();
        var qa = InlineKeyboardButton.builder().text("QA").callbackData("fp:lang:QA").build();
        var data = InlineKeyboardButton.builder().text("Data Analytics").callbackData("fp:lang:DATA_ANALYTICS").build();
        var ba = InlineKeyboardButton.builder().text("Business Analysis").callbackData("fp:lang:BUSINESS_ANALYSIS").build();
        var sa = InlineKeyboardButton.builder().text("System Analysis").callbackData("fp:lang:SYSTEM_ANALYSIS").build();
        var cancel = InlineKeyboardButton.builder().text("Отмена").callbackData("fp:cancel").build();
        return InlineKeyboardMarkup.builder().keyboard(List.of(
                new InlineKeyboardRow(java, python),
                new InlineKeyboardRow(js, go),
                new InlineKeyboardRow(qa, data),
                new InlineKeyboardRow(ba, sa),
                new InlineKeyboardRow(cancel)
        )).build();
    }

    private static InlineKeyboardMarkup scheduleMenuKeyboard() {
        var add = InlineKeyboardButton.builder().text("Добавить слот").callbackData("sc:add").build();
        var remove = InlineKeyboardButton.builder().text("Удалить слот").callbackData("sc:remove").build();
        var cancel = InlineKeyboardButton.builder().text("Закрыть").callbackData("sc:close").build();
        return InlineKeyboardMarkup.builder().keyboard(List.of(
                new InlineKeyboardRow(add),
                new InlineKeyboardRow(remove),
                new InlineKeyboardRow(cancel)
        )).build();
    }

    private static InlineKeyboardMarkup requestKeyboard(Long requestId) {
        var accept = InlineKeyboardButton.builder().text("Принять").callbackData("ir:accept:" + requestId).build();
        var decline = InlineKeyboardButton.builder().text("Отклонить").callbackData("ir:decline:" + requestId).build();
        List<InlineKeyboardRow> rows = List.of(new InlineKeyboardRow(accept, decline));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private void handleScheduleCallback(Long chatId, Integer messageId, String data, TelegramClient telegramClient) throws TelegramApiException {
        if (data.equals("sc:noop")) return;
        if (data.equals("sc:close")) {
            stateService.clearSchedule(chatId);
            telegramClient.execute(SendMessage.builder().chatId(chatId).text("Ок.").build());
            sendMainMenu(chatId, telegramClient);
            return;
        }
        ScheduleState state = stateService.getSchedule(chatId).orElse(null);
        if (state == null) {
            telegramClient.execute(SendMessage.builder().chatId(chatId).text("Сессия истекла. Откройте заново: /schedule").replyMarkup(ChatMenuKeyboardBuilder.buildPersistentKeyboard()).build());
            return;
        }
        if (data.startsWith("sc:lang:")) {
            Language lang = Language.valueOf(data.substring("sc:lang:".length()));
            state.language = lang;
            List<Schedule> slots = scheduleService.getUserSchedule(state.userId);
            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(AvailabilityCommandHandler.renderSchedule(slots, lang))
                    .replyMarkup(AvailabilityCommandHandler.scheduleMenuKeyboard())
                    .build());
            return;
        }
        if (data.equals("sc:add")) {
            var now = YearMonth.now();
            state.calendarYear = now.getYear();
            state.calendarMonth = now.getMonthValue();
            state.selectedDaysForSlot.clear();
            state.step = ScheduleState.Step.CALENDAR;
            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("Тыкайте на дни — отмечены ✓. Потом нажмите «Задать время» (слоты повторяются еженедельно).")
                    .replyMarkup(calendarMonthKeyboard(state.calendarYear, state.calendarMonth, state.userId, state.selectedDaysForSlot))
                    .build());
            return;
        }
        if (data.startsWith("sc:calnav:")) {
            String ym = data.substring("sc:calnav:".length());
            String[] parts = ym.split("-");
            if (parts.length != 2) return;
            int y = Integer.parseInt(parts[0]);
            int m = Integer.parseInt(parts[1]);
            state.calendarYear = y;
            state.calendarMonth = m;
            InlineKeyboardMarkup keyboard = calendarMonthKeyboard(state.calendarYear, state.calendarMonth, state.userId, state.selectedDaysForSlot);
            if (messageId != null) {
                telegramClient.execute(EditMessageReplyMarkup.builder().chatId(chatId).messageId(messageId).replyMarkup(keyboard).build());
            } else {
                telegramClient.execute(SendMessage.builder().chatId(chatId).text("Выберите день:").replyMarkup(keyboard).build());
            }
            return;
        }
        if (data.startsWith("sc:calday:")) {
            String dateStr = data.substring("sc:calday:".length());
            LocalDate day = LocalDate.parse(dateStr);
            DayOfWeek w = day.getDayOfWeek();
            if (state.selectedDaysForSlot.contains(w)) {
                state.selectedDaysForSlot.remove(w);
            } else {
                state.selectedDaysForSlot.add(w);
            }
            InlineKeyboardMarkup keyboard = calendarMonthKeyboard(state.calendarYear, state.calendarMonth, state.userId, state.selectedDaysForSlot);
            if (messageId != null) {
                telegramClient.execute(EditMessageReplyMarkup.builder().chatId(chatId).messageId(messageId).replyMarkup(keyboard).build());
            } else {
                telegramClient.execute(SendMessage.builder().chatId(chatId).text("Выберите день:").replyMarkup(keyboard).build());
            }
            return;
        }
        if (data.equals("sc:calconfirm")) {
            if (state.selectedDaysForSlot.isEmpty()) {
                telegramClient.execute(SendMessage.builder().chatId(chatId).text("Сначала отметьте дни в календаре.").build());
                return;
            }
            state.step = ScheduleState.Step.ADD_TIME;
            state.dayOfWeek = null;
            String daysStr = state.selectedDaysForSlot.stream().sorted().map(CallbackQueryHandler::formatDayOfWeekRu).reduce((a, b) -> a + ", " + b).orElse("");
            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("Введите время для выбранных дней (" + daysStr + ") в формате HH:mm-HH:mm (например 10:00-12:00):")
                    .build());
            return;
        }
        if (data.equals("sc:remove")) {
            var slots = scheduleService.getUserSchedule(state.userId);
            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(slots.isEmpty() ? "Удалять нечего — расписание пустое." : "Выберите слот для удаления:")
                    .replyMarkup(slots.isEmpty() ? scheduleMenuKeyboard() : removeKeyboard(slots))
                    .build());
            return;
        }
        if (data.startsWith("sc:day:")) {
            state.dayOfWeek = DayOfWeek.valueOf(data.substring("sc:day:".length()));
            state.step = ScheduleState.Step.ADD_TIME;
            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("Введите время слота в формате HH:mm-HH:mm (например 10:00-12:00)")
                    .build());
            return;
        }
        if (data.startsWith("sc:del:")) {
            Long id = Long.parseLong(data.substring("sc:del:".length()));
            scheduleService.removeAvailability(id);
            telegramClient.execute(SendMessage.builder().chatId(chatId).text("Слот удалён.").build());
            List<Schedule> slots = scheduleService.getUserSchedule(state.userId);
            Language stateLanguage = state.language != null ? state.language :
                    (slots.isEmpty() ? null : slots.get(0).getLanguage());
            if (stateLanguage == null) {
                telegramClient.execute(SendMessage.builder().chatId(chatId)
                        .text("Укажите направление:").replyMarkup(AvailabilityCommandHandler.languageKeyboard()).build());
            } else {
                telegramClient.execute(SendMessage.builder().chatId(chatId)
                        .text(AvailabilityCommandHandler.renderSchedule(slots, stateLanguage))
                        .replyMarkup(AvailabilityCommandHandler.scheduleMenuKeyboard()).build());
            }
        }
    }

    private static final String[] DAY_NAMES_RU = new String[]{"Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"};
    private static final String[] MONTH_NAMES_RU = new String[]{"Янв", "Фев", "Мар", "Апр", "Май", "Июн", "Июл", "Авг", "Сен", "Окт", "Ноя", "Дек"};

    private static String formatDayOfWeekRu(DayOfWeek d) {
        return DAY_NAMES_RU[d.getValue() - 1];
    }

    private InlineKeyboardMarkup calendarMonthKeyboard(int year, int month, Long userId, Set<DayOfWeek> selectedDays) {
        if (selectedDays == null) selectedDays = Set.of();
        YearMonth ym = YearMonth.of(year, month);
        Set<DayOfWeek> daysWithSlots = scheduleService.getUserSchedule(userId).stream()
                .map(Schedule::getDayOfWeek)
                .collect(Collectors.toSet());
        List<InlineKeyboardRow> rows = new ArrayList<>();
        String monthTitle = MONTH_NAMES_RU[month - 1] + " " + year;
        YearMonth prev = ym.minusMonths(1);
        YearMonth next = ym.plusMonths(1);
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("◀").callbackData("sc:calnav:" + prev.getYear() + "-" + prev.getMonthValue()).build(),
                InlineKeyboardButton.builder().text(monthTitle).callbackData("sc:noop").build(),
                InlineKeyboardButton.builder().text("▶").callbackData("sc:calnav:" + next.getYear() + "-" + next.getMonthValue()).build()
        ));
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("Пн").callbackData("sc:noop").build(),
                InlineKeyboardButton.builder().text("Вт").callbackData("sc:noop").build(),
                InlineKeyboardButton.builder().text("Ср").callbackData("sc:noop").build(),
                InlineKeyboardButton.builder().text("Чт").callbackData("sc:noop").build(),
                InlineKeyboardButton.builder().text("Пт").callbackData("sc:noop").build(),
                InlineKeyboardButton.builder().text("Сб").callbackData("sc:noop").build(),
                InlineKeyboardButton.builder().text("Вс").callbackData("sc:noop").build()
        ));
        int firstDay = ym.atDay(1).getDayOfWeek().getValue();
        int len = ym.lengthOfMonth();
        List<InlineKeyboardButton> week = new ArrayList<>();
        for (int i = 1; i < firstDay; i++) {
            week.add(InlineKeyboardButton.builder().text(" ").callbackData("sc:noop").build());
        }
        for (int day = 1; day <= len; day++) {
            LocalDate date = ym.atDay(day);
            DayOfWeek w = date.getDayOfWeek();
            boolean marked = daysWithSlots.contains(w) || selectedDays.contains(w);
            String label = marked ? day + " ✓" : String.valueOf(day);
            week.add(InlineKeyboardButton.builder().text(label).callbackData("sc:calday:" + date).build());
            if (week.size() == 7) {
                rows.add(new InlineKeyboardRow(week));
                week = new ArrayList<>();
            }
        }
        if (!week.isEmpty()) {
            while (week.size() < 7) week.add(InlineKeyboardButton.builder().text(" ").callbackData("sc:noop").build());
            rows.add(new InlineKeyboardRow(week));
        }
        if (!selectedDays.isEmpty()) {
            int n = selectedDays.size();
            String btnText = n == 1 ? "Задать время (1 день)" : "Задать время (" + n + " дн.)";
            rows.add(new InlineKeyboardRow(InlineKeyboardButton.builder().text(btnText).callbackData("sc:calconfirm").build()));
        }
        rows.add(new InlineKeyboardRow(InlineKeyboardButton.builder().text("Отмена").callbackData("sc:close").build()));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private static InlineKeyboardMarkup dayKeyboard() {
        List<InlineKeyboardRow> rows = List.of(
                new InlineKeyboardRow(InlineKeyboardButton.builder().text("Пн").callbackData("sc:day:MONDAY").build(),
                        InlineKeyboardButton.builder().text("Вт").callbackData("sc:day:TUESDAY").build(),
                        InlineKeyboardButton.builder().text("Ср").callbackData("sc:day:WEDNESDAY").build()),
                new InlineKeyboardRow(InlineKeyboardButton.builder().text("Чт").callbackData("sc:day:THURSDAY").build(),
                        InlineKeyboardButton.builder().text("Пт").callbackData("sc:day:FRIDAY").build(),
                        InlineKeyboardButton.builder().text("Сб").callbackData("sc:day:SATURDAY").build()),
                new InlineKeyboardRow(InlineKeyboardButton.builder().text("Вс").callbackData("sc:day:SUNDAY").build(),
                        InlineKeyboardButton.builder().text("Отмена").callbackData("sc:close").build())
        );
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private static InlineKeyboardMarkup removeKeyboard(List<com.interviewpartner.bot.model.Schedule> slots) {
        List<InlineKeyboardRow> rows = slots.stream()
                .map(s -> new InlineKeyboardRow(InlineKeyboardButton.builder()
                        .text(s.getDayOfWeek() + " " + s.getStartTime() + "-" + s.getEndTime())
                        .callbackData("sc:del:" + s.getId())
                        .build()))
                .toList();
        if (rows.isEmpty()) {
            rows = List.of(new InlineKeyboardRow(InlineKeyboardButton.builder().text("Закрыть").callbackData("sc:close").build()));
        }
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    // ---- Candidate slot flow (cs:) ----

    private void handleCandidateSlotCallback(Long chatId, Integer messageId, String data, TelegramClient telegramClient) throws TelegramApiException {
        if (data.equals("cs:noop")) return;
        if (data.equals("cs:close")) {
            stateService.clearCandidateSlot(chatId);
            telegramClient.execute(SendMessage.builder().chatId(chatId).text("Ок.").replyMarkup(ChatMenuKeyboardBuilder.buildPersistentKeyboard()).build());
            return;
        }
        CandidateSlotState state = stateService.getCandidateSlot(chatId).orElse(null);
        if (state == null) {
            telegramClient.execute(SendMessage.builder().chatId(chatId)
                    .text("Сессия истекла. Нажмите «Записаться на собеседование» снова.")
                    .replyMarkup(ChatMenuKeyboardBuilder.buildPersistentKeyboard()).build());
            return;
        }
        if (data.startsWith("cs:lang:")) {
            Language lang = Language.valueOf(data.substring("cs:lang:".length()));
            state.language = lang;
            List<CandidateSlot> slots = candidateSlotService.getUserSlots(state.userId);
            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(CandidateSlotCommandHandler.renderSlots(slots, lang))
                    .replyMarkup(CandidateSlotCommandHandler.slotMenuKeyboard())
                    .build());
            return;
        }
        if (data.equals("cs:add")) {
            if (state.language == null) {
                telegramClient.execute(SendMessage.builder().chatId(chatId)
                        .text("Сначала выберите направление:")
                        .replyMarkup(CandidateSlotCommandHandler.languageKeyboard()).build());
                return;
            }
            var now = YearMonth.now();
            state.calendarYear = now.getYear();
            state.calendarMonth = now.getMonthValue();
            state.selectedDaysForSlot.clear();
            state.step = CandidateSlotState.Step.CALENDAR;
            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("Отметьте дни, когда хотите проходить собеседование ✓. Затем нажмите «Задать время».")
                    .replyMarkup(candidateCalendarMonthKeyboard(state.calendarYear, state.calendarMonth, state.userId, state.selectedDaysForSlot))
                    .build());
            return;
        }
        if (data.startsWith("cs:calnav:")) {
            String ym = data.substring("cs:calnav:".length());
            String[] parts = ym.split("-");
            if (parts.length != 2) return;
            state.calendarYear = Integer.parseInt(parts[0]);
            state.calendarMonth = Integer.parseInt(parts[1]);
            InlineKeyboardMarkup keyboard = candidateCalendarMonthKeyboard(state.calendarYear, state.calendarMonth, state.userId, state.selectedDaysForSlot);
            if (messageId != null) {
                telegramClient.execute(EditMessageReplyMarkup.builder().chatId(chatId).messageId(messageId).replyMarkup(keyboard).build());
            } else {
                telegramClient.execute(SendMessage.builder().chatId(chatId).text("Выберите день:").replyMarkup(keyboard).build());
            }
            return;
        }
        if (data.startsWith("cs:calday:")) {
            String dateStr = data.substring("cs:calday:".length());
            LocalDate day = LocalDate.parse(dateStr);
            DayOfWeek w = day.getDayOfWeek();
            if (state.selectedDaysForSlot.contains(w)) {
                state.selectedDaysForSlot.remove(w);
            } else {
                state.selectedDaysForSlot.add(w);
            }
            InlineKeyboardMarkup keyboard = candidateCalendarMonthKeyboard(state.calendarYear, state.calendarMonth, state.userId, state.selectedDaysForSlot);
            if (messageId != null) {
                telegramClient.execute(EditMessageReplyMarkup.builder().chatId(chatId).messageId(messageId).replyMarkup(keyboard).build());
            } else {
                telegramClient.execute(SendMessage.builder().chatId(chatId).text("Выберите день:").replyMarkup(keyboard).build());
            }
            return;
        }
        if (data.equals("cs:calconfirm")) {
            if (state.selectedDaysForSlot.isEmpty()) {
                telegramClient.execute(SendMessage.builder().chatId(chatId).text("Сначала отметьте дни в календаре.").build());
                return;
            }
            state.step = CandidateSlotState.Step.ADD_TIME;
            state.dayOfWeek = null;
            String daysStr = state.selectedDaysForSlot.stream().sorted().map(CallbackQueryHandler::formatDayOfWeekRu).reduce((a, b) -> a + ", " + b).orElse("");
            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("Введите время для выбранных дней (" + daysStr + ") в формате HH:mm-HH:mm (например 10:00-12:00):")
                    .build());
            return;
        }
        if (data.equals("cs:remove")) {
            List<CandidateSlot> slots = candidateSlotService.getUserSlots(state.userId);
            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(slots.isEmpty() ? "Удалять нечего — слотов нет." : "Выберите слот для удаления:")
                    .replyMarkup(slots.isEmpty() ? CandidateSlotCommandHandler.slotMenuKeyboard() : removeCandidateKeyboard(slots))
                    .build());
            return;
        }
        if (data.startsWith("cs:del:")) {
            Long id = Long.parseLong(data.substring("cs:del:".length()));
            candidateSlotService.removeSlot(id);
            List<CandidateSlot> slots = candidateSlotService.getUserSlots(state.userId);
            Language lang = state.language != null ? state.language :
                    (slots.isEmpty() ? null : slots.get(0).getLanguage());
            telegramClient.execute(SendMessage.builder().chatId(chatId).text("Слот удалён.").build());
            telegramClient.execute(SendMessage.builder().chatId(chatId)
                    .text(CandidateSlotCommandHandler.renderSlots(slots, lang))
                    .replyMarkup(CandidateSlotCommandHandler.slotMenuKeyboard()).build());
        }
    }

    private InlineKeyboardMarkup candidateCalendarMonthKeyboard(int year, int month, Long userId, Set<DayOfWeek> selectedDays) {
        if (selectedDays == null) selectedDays = Set.of();
        YearMonth ym = YearMonth.of(year, month);
        Set<DayOfWeek> daysWithSlots = candidateSlotService.getUserSlots(userId).stream()
                .map(CandidateSlot::getDayOfWeek)
                .collect(Collectors.toSet());
        List<InlineKeyboardRow> rows = new ArrayList<>();
        String monthTitle = MONTH_NAMES_RU[month - 1] + " " + year;
        YearMonth prev = ym.minusMonths(1);
        YearMonth next = ym.plusMonths(1);
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("◀").callbackData("cs:calnav:" + prev.getYear() + "-" + prev.getMonthValue()).build(),
                InlineKeyboardButton.builder().text(monthTitle).callbackData("cs:noop").build(),
                InlineKeyboardButton.builder().text("▶").callbackData("cs:calnav:" + next.getYear() + "-" + next.getMonthValue()).build()
        ));
        rows.add(new InlineKeyboardRow(
                InlineKeyboardButton.builder().text("Пн").callbackData("cs:noop").build(),
                InlineKeyboardButton.builder().text("Вт").callbackData("cs:noop").build(),
                InlineKeyboardButton.builder().text("Ср").callbackData("cs:noop").build(),
                InlineKeyboardButton.builder().text("Чт").callbackData("cs:noop").build(),
                InlineKeyboardButton.builder().text("Пт").callbackData("cs:noop").build(),
                InlineKeyboardButton.builder().text("Сб").callbackData("cs:noop").build(),
                InlineKeyboardButton.builder().text("Вс").callbackData("cs:noop").build()
        ));
        int firstDay = ym.atDay(1).getDayOfWeek().getValue();
        int len = ym.lengthOfMonth();
        List<InlineKeyboardButton> week = new ArrayList<>();
        for (int i = 1; i < firstDay; i++) {
            week.add(InlineKeyboardButton.builder().text(" ").callbackData("cs:noop").build());
        }
        for (int day = 1; day <= len; day++) {
            LocalDate date = ym.atDay(day);
            DayOfWeek w = date.getDayOfWeek();
            boolean marked = daysWithSlots.contains(w) || selectedDays.contains(w);
            String label = marked ? day + " ✓" : String.valueOf(day);
            week.add(InlineKeyboardButton.builder().text(label).callbackData("cs:calday:" + date).build());
            if (week.size() == 7) {
                rows.add(new InlineKeyboardRow(week));
                week = new ArrayList<>();
            }
        }
        if (!week.isEmpty()) {
            while (week.size() < 7) week.add(InlineKeyboardButton.builder().text(" ").callbackData("cs:noop").build());
            rows.add(new InlineKeyboardRow(week));
        }
        if (!selectedDays.isEmpty()) {
            int n = selectedDays.size();
            String btnText = n == 1 ? "Задать время (1 день)" : "Задать время (" + n + " дн.)";
            rows.add(new InlineKeyboardRow(InlineKeyboardButton.builder().text(btnText).callbackData("cs:calconfirm").build()));
        }
        rows.add(new InlineKeyboardRow(InlineKeyboardButton.builder().text("Отмена").callbackData("cs:close").build()));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private static InlineKeyboardMarkup removeCandidateKeyboard(List<CandidateSlot> slots) {
        List<InlineKeyboardRow> rows = slots.stream()
                .map(s -> new InlineKeyboardRow(InlineKeyboardButton.builder()
                        .text(s.getDayOfWeek() + " " + s.getStartTime() + "-" + s.getEndTime())
                        .callbackData("cs:del:" + s.getId())
                        .build()))
                .toList();
        if (rows.isEmpty()) {
            rows = List.of(new InlineKeyboardRow(InlineKeyboardButton.builder().text("Закрыть").callbackData("cs:close").build()));
        }
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    /** Проверяет, соответствует ли интервью выбранному фильтру расписания. */
    private static boolean scheduleFilterMatches(Interview i, InterviewCalendarState.ScheduleFilter filter) {
        if (filter == null) return true;
        boolean isSolo = i.getCandidate() != null && i.getInterviewer() != null
                && i.getCandidate().getId() != null
                && i.getCandidate().getId().equals(i.getInterviewer().getId());
        return switch (filter) {
            case PENDING -> isSolo;
            case CONFIRMED -> !isSolo;
        };
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
        InlineKeyboardMarkup keyboard = ScheduleCommandHandler.buildFilterSelectionKeyboard();
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
