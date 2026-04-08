package com.interviewpartner.bot.telegram.flow;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MVP-хранилище состояния диалогов в памяти.
 * Позже можно заменить на таблицу в БД.
 */
@Service
public class ConversationStateService {

    private final Map<Long, CreateInterviewState> createInterviewStates = new ConcurrentHashMap<>();
    private final Map<Long, ScheduleState> scheduleStates = new ConcurrentHashMap<>();
    private final Map<Long, CandidateSlotState> candidateSlotStates = new ConcurrentHashMap<>();
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

    public ScheduleState startSchedule(Long chatId, Long userId) {
        var state = new ScheduleState();
        state.userId = userId;
        scheduleStates.put(chatId, state);
        return state;
    }

    public Optional<ScheduleState> getSchedule(Long chatId) {
        return Optional.ofNullable(scheduleStates.get(chatId));
    }

    public void clearSchedule(Long chatId) {
        scheduleStates.remove(chatId);
    }

    public CandidateSlotState startCandidateSlot(Long chatId, Long userId) {
        var state = new CandidateSlotState();
        state.userId = userId;
        candidateSlotStates.put(chatId, state);
        return state;
    }

    public Optional<CandidateSlotState> getCandidateSlot(Long chatId) {
        return Optional.ofNullable(candidateSlotStates.get(chatId));
    }

    public void clearCandidateSlot(Long chatId) {
        candidateSlotStates.remove(chatId);
    }

    public InterviewCalendarState startInterviewCalendar(Long chatId, Long userId) {
        var state = new InterviewCalendarState();
        state.userId = userId;
        var now = java.time.LocalDate.now();
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

