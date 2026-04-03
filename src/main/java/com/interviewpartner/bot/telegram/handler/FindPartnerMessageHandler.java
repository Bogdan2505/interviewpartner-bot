package com.interviewpartner.bot.telegram.handler;

import com.interviewpartner.bot.model.User;
import com.interviewpartner.bot.service.InterviewService;
import com.interviewpartner.bot.telegram.flow.ConversationStateService;
import com.interviewpartner.bot.telegram.flow.FindPartnerState;
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

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

@Order(18)
@Component
@RequiredArgsConstructor
public class FindPartnerMessageHandler implements BotCommandHandler {

    private static final DateTimeFormatter DT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ConversationStateService stateService;
    private final InterviewService interviewService;
    private final Clock clock;

    @Override
    public boolean canHandle(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return false;
        }
        if (ChatMenuKeyboardBuilder.isMenuButton(update.getMessage().getText())) {
            return false;
        }
        return stateService.getFindPartner(update.getMessage().getChatId()).isPresent();
    }

    @Override
    public void handle(Update update, TelegramClient telegramClient) {
        Long chatId = update.getMessage().getChatId();
        var stateOpt = stateService.getFindPartner(chatId);
        if (stateOpt.isEmpty()) {
            return;
        }
        var state = stateOpt.get();
        if (state.step != FindPartnerState.Step.DATE_TIME) {
            return;
        }
        String text = update.getMessage().getText().strip();
        LocalDateTime dt;
        try {
            dt = LocalDateTime.parse(text, DT_FORMAT);
        } catch (DateTimeParseException e) {
            try {
                telegramClient.execute(SendMessage.builder().chatId(chatId)
                        .text("Неверный формат. Введите yyyy-MM-dd HH:mm (например 2026-03-25 19:00), часовой пояс: "
                                + clock.getZone().getId())
                        .build());
            } catch (TelegramApiException ex) {
                throw new RuntimeException(ex);
            }
            return;
        }
        if (dt.isBefore(LocalDateTime.now(clock))) {
            try {
                telegramClient.execute(SendMessage.builder().chatId(chatId)
                        .text("Нельзя выбрать дату и время в прошлом. Укажите момент не раньше текущего.")
                        .build());
            } catch (TelegramApiException ex) {
                throw new RuntimeException(ex);
            }
            return;
        }
        state.dateTime = dt;

        List<User> partners = interviewService.findAvailablePartners(state.requesterUserId, state.language, dt);
        String header = partners.isEmpty()
                ? "Никого не нашёл на это время. Попробуйте другое время."
                : "Нашёл партнёров. Выберите:";
        try {
            var msg = SendMessage.builder()
                    .chatId(chatId)
                    .text(header)
                    .replyMarkup(partnersKeyboard(partners))
                    .build();
            telegramClient.execute(msg);
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        } finally {
            stateService.clearFindPartner(chatId);
        }
    }

    private static InlineKeyboardMarkup partnersKeyboard(List<User> partners) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (User u : partners) {
            String label = u.getUsername() != null ? "@" + u.getUsername() : ("User " + u.getId());
            rows.add(new InlineKeyboardRow(InlineKeyboardButton.builder()
                    .text(label)
                    .callbackData("fp:pick:" + u.getId())
                    .build()));
        }
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }
}

