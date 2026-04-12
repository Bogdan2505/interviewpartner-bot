package com.interviewpartner.bot.telegram;

import com.interviewpartner.bot.model.Interview;
import com.interviewpartner.bot.model.InterviewFormat;
import com.interviewpartner.bot.model.Language;
import com.interviewpartner.bot.model.Level;
import com.interviewpartner.bot.model.InterviewRequest;
import com.interviewpartner.bot.model.InterviewRequestStatus;
import com.interviewpartner.bot.model.User;
import com.interviewpartner.bot.service.InterviewService;
import com.interviewpartner.bot.service.UserService;
import com.interviewpartner.bot.service.request.InterviewRequestService;
import com.interviewpartner.bot.telegram.flow.ConversationStateService;
import com.interviewpartner.bot.telegram.handler.CallbackQueryHandler;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InterviewRequestFlowTest {

    private TelegramClient telegramClient;
    private ConversationStateService stateService;
    private InterviewService interviewService;
    private UserService userService;
    private InterviewRequestService interviewRequestService;
    private CallbackQueryHandler handler;

    @BeforeEach
    void setUp() {
        telegramClient = mock(TelegramClient.class);
        stateService = new ConversationStateService(Clock.systemUTC());
        interviewService = mock(InterviewService.class);
        userService = mock(UserService.class);
        interviewRequestService = mock(InterviewRequestService.class);
        InterviewCalendarPresenter calendarPresenter = mock(InterviewCalendarPresenter.class);

        User candidate = mock(User.class);
        when(candidate.getId()).thenReturn(1L);
        when(candidate.getTelegramId()).thenReturn(111L);
        when(userService.getUserById(1L)).thenReturn(candidate);

        User partner = mock(User.class);
        when(partner.getId()).thenReturn(2L);
        when(partner.getTelegramId()).thenReturn(222L);
        when(userService.getUserById(2L)).thenReturn(partner);

        handler = new CallbackQueryHandler(stateService, interviewService, userService, interviewRequestService,
                calendarPresenter, Clock.systemUTC());
    }

    @Test
    void legacyIrAccept_showsStaleMessageAndDoesNotCreateInterview() throws Exception {
        Update update = mockCallbackUpdate(222L, 222L, "ir:accept:10");
        handler.handle(update, telegramClient);

        verify(interviewService, never()).createInterview(anyLong(), anyLong(), any(), any(), any(), any(), anyInt(), anyBoolean());
        verify(telegramClient, org.mockito.Mockito.atLeastOnce()).execute(any(SendMessage.class));
    }

    @Test
    void confirmYes_nonSolo_doesNotCallCreateRequest() throws Exception {
        long chatId = 111L;
        var state = stateService.startCreateInterview(chatId, 1L);
        state.candidateUserId = 1L;
        state.interviewerUserId = 2L;
        state.language = Language.RUSSIAN;
        state.format = InterviewFormat.TECHNICAL;
        state.dateTime = LocalDateTime.of(2026, 4, 20, 19, 0);
        state.durationMinutes = 60;
        state.openSlotRequestId = null;

        Update update = mockCallbackUpdate(chatId, 111L, "ci:confirm:yes");
        handler.handle(update, telegramClient);

        verify(interviewRequestService, never()).createRequest(
                anyLong(), anyLong(), any(), any(), any(), anyInt(), any());
        verify(interviewService, never()).createInterview(
                eq(1L), eq(2L), eq(Language.RUSSIAN), isNull(), eq(InterviewFormat.TECHNICAL), any(), eq(60), eq(true));
        verify(telegramClient, org.mockito.Mockito.atLeastOnce()).execute(any(SendMessage.class));
    }

    @Test
    void confirmYes_whenJoiningOpenSlot_shouldAutoAcceptAndCreateInterview() throws Exception {
        long chatId = 111L;
        var state = stateService.startCreateInterview(chatId, 1L);
        state.candidateUserId = 1L;
        state.interviewerUserId = 2L;
        state.language = Language.RUSSIAN;
        state.format = InterviewFormat.TECHNICAL;
        state.dateTime = LocalDateTime.of(2026, 4, 20, 19, 0);
        state.durationMinutes = 60;
        state.openSlotRequestId = 555L;

        User candidateUser = userService.getUserById(1L);
        User partnerUser = userService.getUserById(2L);
        InterviewRequest acceptedRequest = InterviewRequest.builder()
                .id(555L)
                .slotOwner(partnerUser)
                .language(Language.RUSSIAN)
                .level(Level.SENIOR)
                .format(InterviewFormat.TECHNICAL)
                .dateTime(state.dateTime)
                .durationMinutes(60)
                .status(InterviewRequestStatus.ACCEPTED)
                .createdAt(LocalDateTime.of(2026, 4, 1, 12, 0))
                .build();
        when(interviewRequestService.completeOpenSlotWithJoiner(
                eq(555L), eq(1L), eq(Language.RUSSIAN), eq(InterviewFormat.TECHNICAL), eq(state.dateTime), eq(60), any()))
                .thenReturn(acceptedRequest);

        Interview createdInterview = mock(Interview.class);
        when(createdInterview.getId()).thenReturn(99L);
        when(createdInterview.getVideoMeetingUrl()).thenReturn(null);
        when(createdInterview.getCandidate()).thenReturn(candidateUser);
        when(createdInterview.getInterviewer()).thenReturn(partnerUser);
        when(interviewService.createInterview(eq(1L), eq(2L), eq(Language.RUSSIAN), eq(Level.SENIOR), eq(InterviewFormat.TECHNICAL), any(), eq(60), eq(true)))
                .thenReturn(createdInterview);
        when(interviewService.getInterviewWithParticipants(99L)).thenReturn(createdInterview);

        Update update = mockCallbackUpdate(chatId, 111L, "ci:confirm:yes");
        handler.handle(update, telegramClient);

        verify(interviewRequestService, never()).createRequest(
                anyLong(), anyLong(), any(), any(), any(), anyInt(), any());
        verify(interviewRequestService).completeOpenSlotWithJoiner(
                eq(555L), eq(1L), eq(Language.RUSSIAN), eq(InterviewFormat.TECHNICAL), eq(state.dateTime), eq(60), any());
        verify(interviewService).createInterview(eq(1L), eq(2L), eq(Language.RUSSIAN), eq(Level.SENIOR), eq(InterviewFormat.TECHNICAL), any(), eq(60), eq(true));
        verify(telegramClient, org.mockito.Mockito.atLeastOnce()).execute(any(SendMessage.class));
    }

    private static Update mockCallbackUpdate(Long chatId, Long fromTelegramUserId, String data) {
        Update update = mock(Update.class);
        CallbackQuery callback = mock(CallbackQuery.class);
        Message message = mock(Message.class);
        org.telegram.telegrambots.meta.api.objects.User fromTg = mock(org.telegram.telegrambots.meta.api.objects.User.class);
        when(fromTg.getId()).thenReturn(fromTelegramUserId);
        when(update.hasCallbackQuery()).thenReturn(true);
        when(update.getCallbackQuery()).thenReturn(callback);
        when(callback.getData()).thenReturn(data);
        when(callback.getId()).thenReturn("cb1");
        when(callback.getFrom()).thenReturn(fromTg);
        when(callback.getMessage()).thenReturn(message);
        when(message.getChatId()).thenReturn(chatId);
        return update;
    }
}
