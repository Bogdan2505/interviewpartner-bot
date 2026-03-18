package com.interviewpartner.bot.telegram;

import com.interviewpartner.bot.model.User;
import com.interviewpartner.bot.service.ScheduleService;
import com.interviewpartner.bot.service.UserService;
import com.interviewpartner.bot.telegram.flow.ConversationStateService;
import com.interviewpartner.bot.telegram.handler.CallbackQueryHandler;
import com.interviewpartner.bot.telegram.handler.ScheduleCommandHandler;
import com.interviewpartner.bot.telegram.handler.ScheduleMessageHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduleFlowTest {

    private TelegramClient telegramClient;
    private ConversationStateService stateService;
    private ScheduleCommandHandler scheduleCommandHandler;
    private CallbackQueryHandler callbackQueryHandler;
    private ScheduleMessageHandler scheduleMessageHandler;
    private ScheduleService scheduleService;

    @BeforeEach
    void setUp() {
        telegramClient = mock(TelegramClient.class);
        stateService = new ConversationStateService();
        UserService userService = mock(UserService.class);
        User user = mock(User.class);
        when(user.getId()).thenReturn(1L);
        when(userService.registerUser(anyLong(), any())).thenReturn(user);

        scheduleService = mock(ScheduleService.class);
        scheduleCommandHandler = new ScheduleCommandHandler(userService, scheduleService, stateService);
        callbackQueryHandler = new CallbackQueryHandler(stateService, mock(com.interviewpartner.bot.service.InterviewService.class), scheduleService);
        scheduleMessageHandler = new ScheduleMessageHandler(stateService, scheduleService);
    }

    @Test
    void addSlotFlow_shouldAskDayThenTimeThenSave() throws Exception {
        Update start = mockMessageUpdate(10L, "/schedule");
        scheduleCommandHandler.handle(start, telegramClient);
        verify(telegramClient).execute(any(SendMessage.class));
        reset(telegramClient);

        Update add = mockCallbackUpdate(10L, "sc:add");
        callbackQueryHandler.handle(add, telegramClient);
        verify(telegramClient).execute(any(SendMessage.class));
        reset(telegramClient);

        Update day = mockCallbackUpdate(10L, "sc:day:MONDAY");
        callbackQueryHandler.handle(day, telegramClient);
        verify(telegramClient).execute(any(SendMessage.class));
        reset(telegramClient);

        Update time = mockMessageUpdate(10L, "10:00-12:00");
        scheduleMessageHandler.handle(time, telegramClient);
        verify(scheduleService).addAvailability(anyLong(), any(), any(), any());
        verify(telegramClient).execute(any(SendMessage.class));
    }

    private static Update mockMessageUpdate(Long chatId, String text) {
        Update update = mock(Update.class);
        Message message = mock(Message.class);
        when(update.hasMessage()).thenReturn(true);
        when(update.getMessage()).thenReturn(message);
        when(message.hasText()).thenReturn(true);
        when(message.getText()).thenReturn(text);
        when(message.getChatId()).thenReturn(chatId);
        org.telegram.telegrambots.meta.api.objects.User from = mock(org.telegram.telegrambots.meta.api.objects.User.class);
        when(from.getId()).thenReturn(123L);
        when(from.getUserName()).thenReturn("u");
        when(message.getFrom()).thenReturn(from);
        return update;
    }

    private static Update mockCallbackUpdate(Long chatId, String data) {
        Update update = mock(Update.class);
        CallbackQuery callback = mock(CallbackQuery.class);
        Message message = mock(Message.class);
        when(update.hasCallbackQuery()).thenReturn(true);
        when(update.getCallbackQuery()).thenReturn(callback);
        when(callback.getData()).thenReturn(data);
        when(callback.getId()).thenReturn("cb1");
        when(callback.getMessage()).thenReturn(message);
        when(message.getChatId()).thenReturn(chatId);
        return update;
    }
}

