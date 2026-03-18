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
    private final Map<Long, FindPartnerState> findPartnerStates = new ConcurrentHashMap<>();
    private final Map<Long, ScheduleState> scheduleStates = new ConcurrentHashMap<>();

    public CreateInterviewState startCreateInterview(Long chatId, Long candidateUserId) {
        var state = new CreateInterviewState();
        state.candidateUserId = candidateUserId;
        createInterviewStates.put(chatId, state);
        return state;
    }

    public Optional<CreateInterviewState> getCreateInterview(Long chatId) {
        return Optional.ofNullable(createInterviewStates.get(chatId));
    }

    public void clearCreateInterview(Long chatId) {
        createInterviewStates.remove(chatId);
    }

    public FindPartnerState startFindPartner(Long chatId, Long requesterUserId) {
        var state = new FindPartnerState();
        state.requesterUserId = requesterUserId;
        findPartnerStates.put(chatId, state);
        return state;
    }

    public Optional<FindPartnerState> getFindPartner(Long chatId) {
        return Optional.ofNullable(findPartnerStates.get(chatId));
    }

    public void clearFindPartner(Long chatId) {
        findPartnerStates.remove(chatId);
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
}

