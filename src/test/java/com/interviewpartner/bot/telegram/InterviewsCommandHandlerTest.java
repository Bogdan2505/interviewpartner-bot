package com.interviewpartner.bot.telegram;

import com.interviewpartner.bot.model.Interview;
import com.interviewpartner.bot.model.InterviewFormat;
import com.interviewpartner.bot.model.InterviewStatus;
import com.interviewpartner.bot.model.Language;
import com.interviewpartner.bot.model.User;
import com.interviewpartner.bot.service.InterviewService;
import com.interviewpartner.bot.service.UserService;
import com.interviewpartner.bot.telegram.handler.InterviewsCommandHandler;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InterviewsCommandHandlerTest {

    @Test
    void shouldSendListOfInterviews() throws Exception {
        TelegramClient telegramClient = mock(TelegramClient.class);
        UserService userService = mock(UserService.class);
        InterviewService interviewService = mock(InterviewService.class);

        User user = mock(User.class);
        when(user.getId()).thenReturn(1L);
        when(userService.registerUser(anyLong(), any())).thenReturn(user);

        when(interviewService.getUserInterviews(1L, null)).thenReturn(List.of(
                Interview.builder()
                        .dateTime(LocalDateTime.now().plusDays(1))
                        .language(Language.RUSSIAN)
                        .format(InterviewFormat.TECHNICAL)
                        .duration(60)
                        .status(InterviewStatus.SCHEDULED)
                        .build()
        ));

        InterviewsCommandHandler handler = new InterviewsCommandHandler(userService, interviewService, Clock.systemUTC());
        Update update = mock(Update.class);
        Message message = mock(Message.class);
        org.telegram.telegrambots.meta.api.objects.User from = mock(org.telegram.telegrambots.meta.api.objects.User.class);
        when(update.hasMessage()).thenReturn(true);
        when(update.getMessage()).thenReturn(message);
        when(message.hasText()).thenReturn(true);
        when(message.getText()).thenReturn("/interviews");
        when(message.getChatId()).thenReturn(10L);
        when(message.getFrom()).thenReturn(from);
        when(from.getId()).thenReturn(123L);
        when(from.getUserName()).thenReturn("u");

        handler.handle(update, telegramClient);

        verify(telegramClient, never()).execute(any(SendMessage.class));
    }
}

