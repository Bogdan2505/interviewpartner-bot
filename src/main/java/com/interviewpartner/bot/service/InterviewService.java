package com.interviewpartner.bot.service;

import com.interviewpartner.bot.model.Interview;
import com.interviewpartner.bot.model.InterviewFormat;
import com.interviewpartner.bot.model.InterviewStatus;
import com.interviewpartner.bot.model.Language;
import com.interviewpartner.bot.model.Level;
import com.interviewpartner.bot.model.User;
import com.interviewpartner.bot.service.dto.AvailableSlotDto;

import java.time.LocalDateTime;
import java.util.List;

public interface InterviewService {
    Interview createInterview(
            Long candidateId,
            Long interviewerId,
            Language language,
            Level level,
            InterviewFormat format,
            LocalDateTime dateTime,
            int durationMinutes,
            boolean initiatorIsCandidate
    );

    List<User> findAvailablePartners(Long userId, Language language, LocalDateTime dateTime);

    /**
     * Слоты от других пользователей (solo-слоты интервьюеров), к которым можно присоединиться кандидатом.
     * @param level если не null — фильтрует по уровню
     */
    List<AvailableSlotDto> getAvailableSlotsAsCandidate(Long candidateUserId, Language language, Level level, int daysAhead);

    /**
     * Слоты от других пользователей (solo-слоты кандидатов), к которым можно присоединиться интервьюером.
     * @param level если не null — фильтрует по уровню
     */
    List<AvailableSlotDto> getAvailableSlotsAsInterviewer(Long interviewerUserId, Language language, Level level, int daysAhead);

    /** Пытается автоматически создать собеседование для интервьюера с первым подходящим кандидатом. */
    List<Interview> tryAutoMatchForInterviewer(Long interviewerUserId, Language language);

    /** Пытается автоматически создать собеседование для кандидата с первым подходящим интервьюером. */
    List<Interview> tryAutoMatchForCandidate(Long candidateUserId, Language language);

    /**
     * Присоединяет пользователя к существующему solo-слоту:
     * если asCandidate=true — устанавливает candidateId, иначе — interviewerId.
     */
    Interview joinInterview(Long interviewId, Long userId, boolean asCandidate);

    /**
     * Интервью с подгруженными кандидатом и интервьюером (для текста в Telegram вне транзакции создания).
     */
    Interview getInterviewWithParticipants(Long id);

    List<Interview> getUserInterviews(Long userId, InterviewStatus status);

    Interview cancelInterview(Long interviewId);

    Interview completeInterview(Long interviewId);
}

