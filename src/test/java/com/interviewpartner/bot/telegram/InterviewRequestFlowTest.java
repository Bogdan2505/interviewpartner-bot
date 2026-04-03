package com.interviewpartner.bot.telegram;

import com.interviewpartner.bot.model.Interview;
import com.interviewpartner.bot.model.InterviewFormat;
import com.interviewpartner.bot.model.Language;
import com.interviewpartner.bot.model.InterviewRequest;
import com.interviewpartner.bot.model.InterviewRequestStatus;
import com.interviewpartner.bot.model.User;
import com.interviewpartner.bot.service.InterviewService;
import com.interviewpartner.bot.service.ScheduleService;
import com.interviewpartner.bot.service.UserService;
import com.interviewpartner.bot.service.request.InterviewRequestService;
import com.interviewpartner.bot.telegram.flow.ConversationStateService;
import com.interviewpartner.bot.telegram.handler.CallbackQueryHandler;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
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
        stateService = new ConversationStateService();
        interviewService = mock(InterviewService.class);
        userService = mock(UserService.class);
        interviewRequestService = mock(InterviewRequestService.class);
        ScheduleService scheduleService = mock(ScheduleService.class);

        User candidate = mock(User.class);
        when(candidate.getId()).thenReturn(1L);
        when(candidate.getTelegramId()).thenReturn(111L);
        when(userService.getUserById(1L)).thenReturn(candidate);

        User partner = mock(User.class);
        when(partner.getId()).thenReturn(2L);
        when(partner.getTelegramId()).thenReturn(222L);
        when(userService.getUserById(2L)).thenReturn(partner);

        handler = new CallbackQueryHandler(stateService, interviewService, scheduleService,
                mock(com.interviewpartner.bot.service.CandidateSlotService.class), userService, interviewRequestService,
                Clock.systemUTC());
    }

    @Test
    void acceptRequest_shouldCreateInterviewAndNotifyBoth() throws Exception {
        User candidate = userService.getUserById(1L);
        User partner = userService.getUserById(2L);

        InterviewRequest req = InterviewRequest.builder()
                .id(10L)
                .candidate(candidate)
                .interviewer(partner)
                .language(Language.RUSSIAN)
                .format(InterviewFormat.TECHNICAL)
                .dateTime(LocalDateTime.of(2026, 3, 25, 19, 0))
                .durationMinutes(60)
                .status(InterviewRequestStatus.PENDING)
                .createdAt(LocalDateTime.of(2026, 3, 18, 12, 0))
                .build();

        when(interviewRequestService.accept(eq(10L), eq(222L), any())).thenReturn(req);

        Interview createdInterview = mock(Interview.class);
        when(createdInterview.getId()).thenReturn(99L);
        when(createdInterview.getVideoMeetingUrl()).thenReturn(null);
        when(createdInterview.getCandidate()).thenReturn(candidate);
        when(createdInterview.getInterviewer()).thenReturn(partner);
        when(interviewService.createInterview(eq(1L), eq(2L), eq(Language.RUSSIAN), isNull(), eq(InterviewFormat.TECHNICAL), any(), eq(60), eq(true)))
                .thenReturn(createdInterview);
        when(interviewService.getInterviewWithParticipants(99L)).thenReturn(createdInterview);

        Update update = mockCallbackUpdate(222L, 222L, "ir:accept:10");
        handler.handle(update, telegramClient);

        verify(interviewService).createInterview(eq(1L), eq(2L), eq(Language.RUSSIAN), isNull(), eq(InterviewFormat.TECHNICAL), any(), eq(60), eq(true));
        verify(interviewService).getInterviewWithParticipants(99L);
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

