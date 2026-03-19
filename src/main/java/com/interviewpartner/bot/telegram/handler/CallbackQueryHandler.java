package com.interviewpartner.bot.telegram.handler;

import com.interviewpartner.bot.exception.InterviewConflictException;
import com.interviewpartner.bot.exception.ScheduleOverlapException;
import com.interviewpartner.bot.exception.UserNotFoundException;
import com.interviewpartner.bot.model.InterviewFormat;
import com.interviewpartner.bot.model.Language;
import com.interviewpartner.bot.model.User;
import com.interviewpartner.bot.service.InterviewService;
import com.interviewpartner.bot.service.ScheduleService;
import com.interviewpartner.bot.service.UserService;
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
            String text = switch (safeData) {
                case "cmd:create_interview" -> "Создание собеседования: используйте /create_interview.";
                case "cmd:find_partner" -> "Поиск партнёра (в разработке). Используйте /find_partner.";
                case "cmd:interviews" -> "Мои собеседования (в разработке). Используйте /interviews.";
                case "cmd:schedule" -> "Расписание: используйте /schedule.";
                case "cmd:help" -> "Используйте /help для справки по командам.";
                default -> "Действие в разработке.";
            };
            telegramClient.execute(SendMessage.builder().chatId(chatId).text(text).build());
        } catch (TelegramApiException e) {
            throw new RuntimeException("Failed to handle callback", e);
        }
    }

    private void handleCreateInterviewCallback(Long chatId, String data, TelegramClient telegramClient) throws TelegramApiException {
        if (data.equals("ci:cancel")) {
            stateService.clearCreateInterview(chatId);
            telegramClient.execute(SendMessage.builder().chatId(chatId).text("Ок, отменил создание собеседования.").build());
            return;
        }
        CreateInterviewState state = stateService.getCreateInterview(chatId).orElse(null);
        if (state == null) {
            telegramClient.execute(SendMessage.builder().chatId(chatId).text("Сессия истекла. Начните заново: /create_interview").build());
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
            state.step = CreateInterviewState.Step.DATE_TIME;
            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("Введите дату и время в формате: yyyy-MM-dd HH:mm (например 2026-03-25 19:00)")
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
                } catch (InterviewConflictException e) {
                    telegramClient.execute(SendMessage.builder().chatId(chatId)
                            .text("В это время у вас или партнёра уже есть собеседование. Выберите другое время.").build());
                } catch (UserNotFoundException e) {
                    telegramClient.execute(SendMessage.builder().chatId(chatId).text("Ошибка: пользователь не найден. Начните заново: /create_interview").build());
                }
                stateService.clearCreateInterview(chatId);
            }
        }
    }

    private static InlineKeyboardMarkup confirmKeyboard() {
        var yes = InlineKeyboardButton.builder().text("Создать").callbackData("ci:confirm:yes").build();
        var no = InlineKeyboardButton.builder().text("Отмена").callbackData("ci:confirm:no").build();
        List<InlineKeyboardRow> rows = List.of(new InlineKeyboardRow(yes, no));
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
            return;
        }
        FindPartnerState state = stateService.getFindPartner(chatId).orElse(null);
        if (state == null) {
            telegramClient.execute(SendMessage.builder().chatId(chatId).text("Сессия истекла. Начните заново: /find_partner").build());
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
            User candidate = userService.getUserById(req.getCandidate().getId());
            telegramClient.execute(SendMessage.builder()
                    .chatId(candidate.getTelegramId())
                    .text("Партнёр отклонил запрос на собеседование.")
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
                User candidate = userService.getUserById(req.getCandidate().getId());
                telegramClient.execute(SendMessage.builder()
                        .chatId(candidate.getTelegramId())
                        .text("Партнёр принял запрос. Собеседование создано на " + DT_FORMAT.format(req.getDateTime()) + ".")
                        .build());
            } catch (InterviewConflictException e) {
                telegramClient.execute(SendMessage.builder().chatId(chatId).text("Не могу принять: конфликт по времени.").build());
            }
        }
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
            return;
        }
        ScheduleState state = stateService.getSchedule(chatId).orElse(null);
        if (state == null) {
            telegramClient.execute(SendMessage.builder().chatId(chatId).text("Сессия истекла. Откройте заново: /schedule").build());
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
                    .replyMarkup(removeKeyboard(slots))
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
