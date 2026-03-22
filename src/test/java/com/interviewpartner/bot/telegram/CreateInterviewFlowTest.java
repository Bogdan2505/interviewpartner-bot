package com.interviewpartner.bot.telegram;

import com.interviewpartner.bot.model.User;
import com.interviewpartner.bot.service.InterviewService;
import com.interviewpartner.bot.service.UserService;
import com.interviewpartner.bot.service.request.InterviewRequestService;
import com.interviewpartner.bot.telegram.flow.ConversationStateService;
import com.interviewpartner.bot.telegram.handler.CallbackQueryHandler;
import com.interviewpartner.bot.telegram.handler.CreateInterviewCommandHandler;
import com.interviewpartner.bot.telegram.handler.CreateInterviewMessageHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreateInterviewFlowTest {

    private TelegramClient telegramClient;
    private ConversationStateService stateService;
    private CreateInterviewCommandHandler commandHandler;
    private CallbackQueryHandler callbackQueryHandler;
    private CreateInterviewMessageHandler messageHandler;

    @BeforeEach
    void setUp() {
        telegramClient = mock(TelegramClient.class);
        stateService = new ConversationStateService();
        UserService userService = mock(UserService.class);
        User mockUser = mock(User.class);
        when(mockUser.getId()).thenReturn(1L);
        when(userService.registerUser(anyLong(), any())).thenReturn(mockUser);
        InterviewService interviewService = mock(InterviewService.class);
        when(interviewService.findAvailablePartners(anyLong(), any(), any())).thenReturn(Collections.emptyList());
        when(interviewService.getAvailableSlotsAsCandidate(anyLong(), any(), anyInt())).thenReturn(Collections.emptyList());
        when(interviewService.getAvailableSlotsAsInterviewer(anyLong(), any(), anyInt())).thenReturn(Collections.emptyList());
        commandHandler = new CreateInterviewCommandHandler(stateService, userService);
        callbackQueryHandler = new CallbackQueryHandler(
                stateService,
                interviewService,
                mock(com.interviewpartner.bot.service.ScheduleService.class),
                userService,
                mock(InterviewRequestService.class)
        );
        messageHandler = new CreateInterviewMessageHandler(stateService, interviewService);
    }

    @Test
    void createInterviewFlow_shouldAskLanguageThenFormatThenDateTimeThenDuration() throws Exception {
        Update start = mockMessageUpdate(1L, "/create_interview");
        commandHandler.handle(start, telegramClient);
        verify(telegramClient).execute(any(SendMessage.class));
        reset(telegramClient);

        Update lang = mockCallbackUpdate(1L, "ci:lang:RUSSIAN");
        callbackQueryHandler.handle(lang, telegramClient);
        verify(telegramClient).execute(any(SendMessage.class));
        reset(telegramClient);

        Update format = mockCallbackUpdate(1L, "ci:format:TECHNICAL");
        callbackQueryHandler.handle(format, telegramClient);
        verify(telegramClient).execute(any(SendMessage.class));
        reset(telegramClient);

        Update day = mockCallbackUpdate(1L, "ci:slotdate:2026-03-25");
        callbackQueryHandler.handle(day, telegramClient);
        verify(telegramClient).execute(any(SendMessage.class));
        reset(telegramClient);

        Update time = mockCallbackUpdate(1L, "ci:time:2026-03-25:19");
        callbackQueryHandler.handle(time, telegramClient);
        verify(telegramClient).execute(any(SendMessage.class));
        reset(telegramClient);

        Update partner = mockCallbackUpdate(1L, "ci:partner:self");
        callbackQueryHandler.handle(partner, telegramClient);
        verify(telegramClient).execute(any(SendMessage.class));
        reset(telegramClient);

        Update confirm = mockCallbackUpdate(1L, "ci:confirm:yes");
        callbackQueryHandler.handle(confirm, telegramClient);
        // После подтверждения отправляются сообщение об успехе и главное меню
        verify(telegramClient, atLeast(1)).execute(any(SendMessage.class));
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

