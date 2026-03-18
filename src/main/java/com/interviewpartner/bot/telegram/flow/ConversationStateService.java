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
}

