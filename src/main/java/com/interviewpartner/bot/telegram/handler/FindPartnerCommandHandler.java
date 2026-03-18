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
    private static final String MESSAGE_RU = "Поиск партнёра: выберите язык.";

    private final ConversationStateService stateService;
    private final UserService userService;

    @Override
    public boolean canHandle(Update update) {
        return update.hasMessage() && update.getMessage().hasText()
                && update.getMessage().getText().strip().startsWith(CMD);
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
            stateService.startFindPartner(chatId, user.getId());

            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(MESSAGE_RU)
                    .replyMarkup(languageKeyboard())
                    .build());
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        }
    }

    private static InlineKeyboardMarkup languageKeyboard() {
        var ru = InlineKeyboardButton.builder().text("Русский").callbackData("fp:lang:RUSSIAN").build();
        var en = InlineKeyboardButton.builder().text("English").callbackData("fp:lang:ENGLISH").build();
        var cancel = InlineKeyboardButton.builder().text("Отмена").callbackData("fp:cancel").build();
        List<InlineKeyboardRow> rows = List.of(
                new InlineKeyboardRow(ru, en),
                new InlineKeyboardRow(cancel)
        );
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }
}
