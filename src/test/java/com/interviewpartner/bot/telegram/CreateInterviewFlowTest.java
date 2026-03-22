package com.interviewpartner.bot.telegram;

import com.interviewpartner.bot.model.User;
import com.interviewpartner.bot.service.CandidateSlotService;
import com.interviewpartner.bot.service.InterviewService;
import com.interviewpartner.bot.service.UserService;
import com.interviewpartner.bot.service.request.InterviewRequestService;
import com.interviewpartner.bot.telegram.handler.CreateInterviewMessageHandler;
import com.interviewpartner.bot.telegram.flow.ConversationStateService;
import com.interviewpartner.bot.telegram.handler.CallbackQueryHandler;
import com.interviewpartner.bot.telegram.handler.CreateInterviewCommandHandler;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreateInterviewFlowTest {

    private TelegramClient telegramClient;
    private ConversationStateService stateService;
    private CreateInterviewCommandHandler commandHandler;
    private CallbackQueryHandler callbackQueryHandler;

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
        when(interviewService.tryAutoMatchForCandidate(anyLong(), any())).thenReturn(Collections.emptyList());
        when(interviewService.tryAutoMatchForInterviewer(anyLong(), any())).thenReturn(Collections.emptyList());
        CandidateSlotService candidateSlotService = mock(CandidateSlotService.class);
        when(candidateSlotService.getUserSlots(anyLong())).thenReturn(Collections.emptyList());
        commandHandler = new CreateInterviewCommandHandler(stateService, userService);
        callbackQueryHandler = new CallbackQueryHandler(
                stateService,
                interviewService,
                mock(com.interviewpartner.bot.service.ScheduleService.class),
                candidateSlotService,
                userService,
                mock(InterviewRequestService.class)
        );
    }

    @Test
    void candidateFlow_shouldAskLanguageOnFirstOpen() throws Exception {
        Update start = mockMessageUpdate(1L, "Записаться на собеседование");
        commandHandler.handle(start, telegramClient);
        verify(telegramClient, atLeast(1)).execute(any(SendMessage.class));
    }

    @Test
    void candidateSlotLangCallback_shouldShowSlotMenu() throws Exception {
        Update start = mockMessageUpdate(1L, "Записаться на собеседование");
        commandHandler.handle(start, telegramClient);

        Update lang = mockCallbackUpdate(1L, "cs:lang:JAVA");
        callbackQueryHandler.handle(lang, telegramClient);
        verify(telegramClient, atLeast(2)).execute(any(SendMessage.class));
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
