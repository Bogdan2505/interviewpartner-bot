package com.interviewpartner.bot.telegram.handler;

import com.interviewpartner.bot.exception.InterviewConflictException;
import com.interviewpartner.bot.exception.ScheduleOverlapException;
import com.interviewpartner.bot.exception.UserNotFoundException;
import com.interviewpartner.bot.model.Interview;
import com.interviewpartner.bot.model.InterviewFormat;
import com.interviewpartner.bot.model.InterviewStatus;
import com.interviewpartner.bot.model.Language;
import com.interviewpartner.bot.model.Schedule;
import com.interviewpartner.bot.model.User;
import com.interviewpartner.bot.service.InterviewService;
import com.interviewpartner.bot.service.ScheduleService;
import com.interviewpartner.bot.service.UserService;
import com.interviewpartner.bot.service.dto.AvailableSlotDto;
import com.interviewpartner.bot.telegram.flow.ConversationStateService;
import com.interviewpartner.bot.telegram.flow.CreateInterviewState;
import com.interviewpartner.bot.telegram.flow.FindPartnerState;
import com.interviewpartner.bot.telegram.flow.ScheduleState;
import com.interviewpartner.bot.service.request.InterviewRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

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
            if (safeData.startsWith("sc:")) {
                handleScheduleCallback(chatId, safeData, telegramClient);
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

        if (data.startsWith("ci:lang:")) {
            state.language = Language.valueOf(data.substring("ci:lang:".length()));
            state.step = CreateInterviewState.Step.FORMAT;
            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("Выберите формат собеседования.")
                    .replyMarkup(formatKeyboard())
                    .build());
            return;
        }

        if (data.startsWith("ci:format:")) {
            state.format = InterviewFormat.valueOf(data.substring("ci:format:".length()));
            List<AvailableSlotDto> slots = state.asCandidate
                    ? interviewService.getAvailableSlotsAsCandidate(state.candidateUserId, state.language, 14)
                    : interviewService.getAvailableSlotsAsInterviewer(state.interviewerUserId, state.language, 14);
            if (!slots.isEmpty()) {
                state.availableSlots = slots;
                state.step = CreateInterviewState.Step.VIEW_SLOTS;
                String header = state.asCandidate ? "Доступные слоты (интервьюеры свободны):" : "Доступные слоты (кандидаты свободны):";
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text(header)
                        .replyMarkup(slotsKeyboard(slots))
                        .build());
            } else {
                state.step = CreateInterviewState.Step.DATE_TIME;
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text("Нет подходящих слотов. Введите дату и время вручную: yyyy-MM-dd HH:mm (например 2026-03-25 19:00)")
                        .build());
            }
            return;
        }

        if (data.startsWith("ci:slot:")) {
            String payload = data.substring("ci:slot:".length());
            if ("manual".equals(payload)) {
                state.step = CreateInterviewState.Step.DATE_TIME;
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text("Введите дату и время: yyyy-MM-dd HH:mm (например 2026-03-25 19:00)")
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
            state.dateTime = slot.dateTime();
            state.durationMinutes = 60;
            if (state.asCandidate) {
                state.interviewerUserId = slot.partnerUserId();
            } else {
                state.candidateUserId = slot.partnerUserId();
            }
            state.step = CreateInterviewState.Step.CONFIRM;
            String summary = "Подтвердить создание?\nЯзык: " + state.language + "\nФормат: " + state.format
                    + "\nДата/время: " + DT_FORMAT.format(state.dateTime) + "\nДлительность: " + state.durationMinutes + " мин.\nПартнёр: " + slot.partnerLabel();
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
                    interviewService.createInterview(
                            state.candidateUserId,
                            state.interviewerUserId,
                            state.language,
                            state.format,
                            state.dateTime,
                            state.durationMinutes);
                    telegramClient.execute(SendMessage.builder().chatId(chatId).text("Собеседование создано.").build());
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
                } catch (UserNotFoundException e) {
                    telegramClient.execute(SendMessage.builder().chatId(chatId).text("Ошибка: пользователь не найден. Начните заново: /create_interview").build());
                    stateService.clearCreateInterview(chatId);
                    sendMainMenu(chatId, telegramClient);
                }
            }
        }
    }

    private static InlineKeyboardMarkup confirmKeyboard() {
        var yes = InlineKeyboardButton.builder().text("Создать").callbackData("ci:confirm:yes").build();
        var no = InlineKeyboardButton.builder().text("Отмена").callbackData("ci:confirm:no").build();
        List<InlineKeyboardRow> rows = List.of(new InlineKeyboardRow(yes, no));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private static final DateTimeFormatter SLOT_LABEL = DateTimeFormatter.ofPattern("dd.MM HH:mm");

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

            var req = interviewRequestService.createRequest(
                    state.requesterUserId,
                    partnerUserId,
                    state.language,
                    InterviewFormat.TECHNICAL,
                    state.dateTime,
                    60
            );

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
                interviewService.createInterview(
                        req.getCandidate().getId(),
                        req.getInterviewer().getId(),
                        req.getLanguage(),
                        req.getFormat(),
                        req.getDateTime(),
                        req.getDurationMinutes()
                );
                telegramClient.execute(SendMessage.builder().chatId(chatId).text("Принято. Собеседование создано.").build());
                sendMainMenu(chatId, telegramClient);
                User candidate = userService.getUserById(req.getCandidate().getId());
                telegramClient.execute(SendMessage.builder()
                        .chatId(candidate.getTelegramId())
                        .text("Партнёр принял запрос. Собеседование создано на " + DT_FORMAT.format(req.getDateTime()) + ".")
                        .replyMarkup(ChatMenuKeyboardBuilder.buildPersistentKeyboard())
                        .build());
            } catch (InterviewConflictException e) {
                telegramClient.execute(SendMessage.builder().chatId(chatId).text("Не могу принять: конфликт по времени.").build());
                sendMainMenu(chatId, telegramClient);
            }
        }
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
            case "cmd:interviews" -> {
                String interviewsText = buildInterviewsText(user.getId());
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text(interviewsText)
                        .replyMarkup(ChatMenuKeyboardBuilder.buildPersistentKeyboard())
                        .build());
            }
            case "cmd:schedule" -> {
                stateService.startSchedule(chatId, user.getId());
                sendScheduleMenu(chatId, user.getId(), telegramClient);
            }
            case "cmd:help" -> {
                String helpText = """
                        Справка

                        • Записаться на собеседование — вы кандидат, вам подберут интервьюера.
                        • Провести собеседование — вы интервьюер, проводите встречу с кандидатом.
                        • Мои собеседования — ваши запланированные и прошедшие встречи.
                        • Моё расписание — когда вы свободны для собеседований.
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

    private void sendScheduleMenu(Long chatId, Long userId, TelegramClient telegramClient) throws TelegramApiException {
        List<Schedule> slots = scheduleService.getUserSchedule(userId);
        String text = renderSchedule(slots);
        telegramClient.execute(SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .replyMarkup(scheduleMenuKeyboard())
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

    private void handleScheduleCallback(Long chatId, String data, TelegramClient telegramClient) throws TelegramApiException {
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
        if (data.equals("sc:add")) {
            state.step = ScheduleState.Step.ADD_DAY;
            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("Выберите день недели.")
                    .replyMarkup(dayKeyboard())
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
            sendScheduleMenu(chatId, state.userId, telegramClient);
        }
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
}
