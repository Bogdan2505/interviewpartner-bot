package com.interviewpartner.bot.telegram.handler;

import com.interviewpartner.bot.exception.InterviewConflictException;
import com.interviewpartner.bot.exception.UserNotFoundException;
import com.interviewpartner.bot.model.InterviewFormat;
import com.interviewpartner.bot.model.Language;
import com.interviewpartner.bot.service.InterviewService;
import com.interviewpartner.bot.telegram.flow.ConversationStateService;
import com.interviewpartner.bot.telegram.flow.CreateInterviewState;
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
            String text = switch (safeData) {
                case "cmd:create_interview" -> "Создание собеседования: используйте /create_interview.";
                case "cmd:find_partner" -> "Поиск партнёра (в разработке). Используйте /find_partner.";
                case "cmd:interviews" -> "Мои собеседования (в разработке). Используйте /interviews.";
                case "cmd:schedule" -> "Расписание (в разработке). Используйте /schedule.";
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
}
