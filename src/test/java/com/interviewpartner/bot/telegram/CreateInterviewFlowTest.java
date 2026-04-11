package com.interviewpartner.bot.telegram;

import com.interviewpartner.bot.model.User;
import com.interviewpartner.bot.model.Interview;
import com.interviewpartner.bot.model.InterviewFormat;
import com.interviewpartner.bot.model.InterviewStatus;
import com.interviewpartner.bot.model.Language;
import com.interviewpartner.bot.model.Level;
import com.interviewpartner.bot.service.dto.AvailableSlotDto;
import com.interviewpartner.bot.service.InterviewService;
import com.interviewpartner.bot.service.UserService;
import com.interviewpartner.bot.service.request.InterviewRequestService;
import com.interviewpartner.bot.telegram.handler.CreateInterviewMessageHandler;
import com.interviewpartner.bot.telegram.flow.ConversationStateService;
import com.interviewpartner.bot.telegram.handler.CallbackQueryHandler;
import com.interviewpartner.bot.telegram.handler.CreateInterviewCommandHandler;
import com.interviewpartner.bot.telegram.handler.InterviewCalendarPresenter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreateInterviewFlowTest {

    private TelegramClient telegramClient;
    private ConversationStateService stateService;
    private CreateInterviewCommandHandler commandHandler;
    private CallbackQueryHandler callbackQueryHandler;
    private InterviewService interviewService;

    @BeforeEach
    void setUp() {
        telegramClient = mock(TelegramClient.class);
        stateService = new ConversationStateService(Clock.systemUTC());
        UserService userService = mock(UserService.class);
        User mockUser = mock(User.class);
        when(mockUser.getId()).thenReturn(1L);
        when(userService.registerUser(anyLong(), any())).thenReturn(mockUser);
        interviewService = mock(InterviewService.class);
        when(interviewService.findAvailablePartners(anyLong(), any(), any())).thenReturn(Collections.emptyList());
        when(interviewService.getAvailableSlotsAsCandidate(anyLong(), any(), any(), anyInt())).thenReturn(Collections.emptyList());
        commandHandler = new CreateInterviewCommandHandler(stateService, userService);
        callbackQueryHandler = new CallbackQueryHandler(
                stateService,
                interviewService,
                userService,
                mock(InterviewRequestService.class),
                mock(InterviewCalendarPresenter.class),
                Clock.systemUTC()
        );
    }

    @Test
    void candidateFlow_shouldAskLanguageOnFirstOpen() throws Exception {
        Update start = mockMessageUpdate(1L, "Записаться на собеседование");
        commandHandler.handle(start, telegramClient);
        verify(telegramClient, atLeast(1)).execute(any(SendMessage.class));
    }

    @Test
    void candidateSlotLangCallback_shouldShowDisabledMessage() throws Exception {
        Update start = mockMessageUpdate(1L, "Записаться на собеседование");
        commandHandler.handle(start, telegramClient);

        Update lang = mockCallbackUpdate(1L, "cs:lang:JAVA");
        callbackQueryHandler.handle(lang, telegramClient);
        verify(telegramClient, atLeast(2)).execute(any(SendMessage.class));
    }

    @Test
    void availableSlotsConfirm_shouldBlockJoinWhenTimeConflicts() throws Exception {
        Update start = mockMessageUpdate(1L, "Записаться на собеседование");
        commandHandler.handle(start, telegramClient);

        LocalDateTime slotTime = LocalDateTime.now(Clock.systemUTC()).plusDays(2).withMinute(0).withSecond(0).withNano(0);
        AvailableSlotDto slot = new AvailableSlotDto(slotTime, 42L, "@owner", 555L, Level.MIDDLE);
        when(interviewService.getAvailableSlotsAsCandidate(anyLong(), any(), any(), anyInt())).thenReturn(List.of(slot));

        Interview conflict = mock(Interview.class);
        when(conflict.getId()).thenReturn(777L);
        when(conflict.getDateTime()).thenReturn(slotTime);
        when(conflict.getDuration()).thenReturn(60);
        when(conflict.getStatus()).thenReturn(InterviewStatus.SCHEDULED);
        when(conflict.getLanguage()).thenReturn(Language.JAVA);
        when(conflict.getFormat()).thenReturn(InterviewFormat.TECHNICAL);
        when(interviewService.getUserInterviews(anyLong(), eq(InterviewStatus.SCHEDULED))).thenReturn(List.of(conflict));

        callbackQueryHandler.handle(mockCallbackUpdate(1L, "as:lang:JAVA"), telegramClient);
        callbackQueryHandler.handle(mockCallbackUpdate(1L, "as:slot:0"), telegramClient);
        callbackQueryHandler.handle(mockCallbackUpdate(1L, "as:confirm:yes"), telegramClient);

        verify(interviewService, never()).joinInterview(anyLong(), anyLong(), anyBoolean());
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
