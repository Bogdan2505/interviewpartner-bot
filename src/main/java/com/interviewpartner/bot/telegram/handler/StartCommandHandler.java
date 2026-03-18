package com.interviewpartner.bot.telegram.handler;

import com.interviewpartner.bot.service.UserService;
import com.interviewpartner.bot.model.User;
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

@Order(10)
@Component
@RequiredArgsConstructor
public class StartCommandHandler implements BotCommandHandler {

    private static final String CMD_START = "/start";
    private static final String WELCOME_RU = """
            Добро пожаловать в InterviewPartner Bot!
            Здесь вы можете практиковать собеседования с другими участниками.
            Выберите действие в меню ниже.""";

    private final UserService userService;

    @Override
    public boolean canHandle(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return false;
        }
        String text = update.getMessage().getText().strip();
        return text.equals(CMD_START) || text.startsWith(CMD_START + " ");
    }

    @Override
    public void handle(Update update, TelegramClient telegramClient) {
        var message = update.getMessage();
        var chatId = message.getChatId();
        var from = message.getFrom();
        if (from == null) {
            return;
        }
        Long telegramId = from.getId();
        String username = from.getUserName() != null ? from.getUserName() : from.getFirstName();

        User user = userService.registerUser(telegramId, username != null ? username : "user");

        SendMessage send = SendMessage.builder()
                .chatId(chatId)
                .text(WELCOME_RU)
                .replyMarkup(buildMainMenu())
                .build();
        try {
            telegramClient.execute(send);
        } catch (TelegramApiException e) {
            throw new RuntimeException("Failed to send /start reply", e);
        }
    }

    private InlineKeyboardMarkup buildMainMenu() {
        var createInterview = InlineKeyboardButton.builder()
                .text("Создать собеседование")
                .callbackData("cmd:create_interview")
                .build();
        var findPartner = InlineKeyboardButton.builder()
                .text("Найти партнёра")
                .callbackData("cmd:find_partner")
                .build();
        var myInterviews = InlineKeyboardButton.builder()
                .text("Мои собеседования")
                .callbackData("cmd:interviews")
                .build();
        var schedule = InlineKeyboardButton.builder()
                .text("Расписание")
                .callbackData("cmd:schedule")
                .build();
        var help = InlineKeyboardButton.builder()
                .text("Помощь")
                .callbackData("cmd:help")
                .build();

        List<InlineKeyboardRow> rows = List.of(
                new InlineKeyboardRow(createInterview),
                new InlineKeyboardRow(findPartner),
                new InlineKeyboardRow(myInterviews),
                new InlineKeyboardRow(schedule),
                new InlineKeyboardRow(help));
        return InlineKeyboardMarkup.builder()
                .keyboard(rows)
                .build();
    }
}
