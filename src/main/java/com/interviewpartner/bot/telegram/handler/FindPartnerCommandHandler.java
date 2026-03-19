package com.interviewpartner.bot.telegram.handler;

import com.interviewpartner.bot.model.User;
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

@Order(21)
@Component
@RequiredArgsConstructor
public class FindPartnerCommandHandler implements BotCommandHandler {

    private static final String CMD = "/find_partner";
    private static final String MESSAGE_RU = "Поиск партнёра: выберите направление.";

    private final ConversationStateService stateService;
    private final UserService userService;

    @Override
    public boolean canHandle(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return false;
        }
        String text = update.getMessage().getText().strip();
        return text.startsWith(CMD) || text.equalsIgnoreCase(ChatMenuKeyboardBuilder.BTN_FIND_PARTNER);
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
            Long telegramId = from.getId();
            String username = from.getUserName() != null ? from.getUserName() : from.getFirstName();
            User user = userService.registerUser(telegramId, username != null ? username : "user");
            stateService.startCreateInterview(chatId, user.getId(), false);

            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("Провести собеседование: выберите направление.")
                    .replyMarkup(languageKeyboard())
                    .build());
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        }
    }

    /** Общий flow с созданием собеседования — те же callback ci:lang:, ci:cancel. */
    private static InlineKeyboardMarkup languageKeyboard() {
        var java = InlineKeyboardButton.builder().text("Java").callbackData("ci:lang:JAVA").build();
        var python = InlineKeyboardButton.builder().text("Python").callbackData("ci:lang:PYTHON").build();
        var js = InlineKeyboardButton.builder().text("JavaScript").callbackData("ci:lang:JAVASCRIPT").build();
        var go = InlineKeyboardButton.builder().text("Go").callbackData("ci:lang:GO").build();
        var qa = InlineKeyboardButton.builder().text("QA").callbackData("ci:lang:QA").build();
        var data = InlineKeyboardButton.builder().text("Data Analytics").callbackData("ci:lang:DATA_ANALYTICS").build();
        var ba = InlineKeyboardButton.builder().text("Business Analysis").callbackData("ci:lang:BUSINESS_ANALYSIS").build();
        var sa = InlineKeyboardButton.builder().text("System Analysis").callbackData("ci:lang:SYSTEM_ANALYSIS").build();
        var cancel = InlineKeyboardButton.builder().text("Отмена").callbackData("ci:cancel").build();
        List<InlineKeyboardRow> rows = List.of(
                new InlineKeyboardRow(java, python),
                new InlineKeyboardRow(js, go),
                new InlineKeyboardRow(qa, data),
                new InlineKeyboardRow(ba, sa),
                new InlineKeyboardRow(cancel)
        );
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }
}
