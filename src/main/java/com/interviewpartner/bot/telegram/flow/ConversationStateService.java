package com.interviewpartner.bot.telegram.flow;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MVP-хранилище состояния диалогов в памяти.
 * Позже можно заменить на таблицу в БД.
 */
@Service
@RequiredArgsConstructor
public class ConversationStateService {

    private final Clock clock;

    private final Map<Long, CreateInterviewState> createInterviewStates = new ConcurrentHashMap<>();
    private final Map<Long, InterviewCalendarState> interviewCalendarStates = new ConcurrentHashMap<>();

    /** Поток «Записаться на собеседование» (взаимный час). */
    public CreateInterviewState startCreateInterview(Long chatId, Long userId) {
        var state = new CreateInterviewState();
        state.candidateUserId = userId;
        createInterviewStates.put(chatId, state);
        return state;
    }

    public Optional<CreateInterviewState> getCreateInterview(Long chatId) {
        return Optional.ofNullable(createInterviewStates.get(chatId));
    }

    public void clearCreateInterview(Long chatId) {
        createInterviewStates.remove(chatId);
    }

    public InterviewCalendarState startInterviewCalendar(Long chatId, Long userId) {
        var state = new InterviewCalendarState();
        state.userId = userId;
        LocalDate now = LocalDate.now(clock);
        state.calendarYear = now.getYear();
        state.calendarMonth = now.getMonthValue();
        state.selectedDate = null;
        interviewCalendarStates.put(chatId, state);
        return state;
    }

    public Optional<InterviewCalendarState> getInterviewCalendar(Long chatId) {
        return Optional.ofNullable(interviewCalendarStates.get(chatId));
    }

    public void clearInterviewCalendar(Long chatId) {
        interviewCalendarStates.remove(chatId);
    }
}

