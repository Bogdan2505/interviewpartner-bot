package com.interviewpartner.bot.service;

import com.interviewpartner.bot.model.Interview;
import com.interviewpartner.bot.model.InterviewFormat;
import com.interviewpartner.bot.model.InterviewStatus;
import com.interviewpartner.bot.model.Language;
import com.interviewpartner.bot.model.User;
import com.interviewpartner.bot.service.dto.AvailableSlotDto;

import java.time.LocalDateTime;
import java.util.List;

public interface InterviewService {
    Interview createInterview(
            Long candidateId,
            Long interviewerId,
            Language language,
            InterviewFormat format,
            LocalDateTime dateTime,
            int durationMinutes,
            boolean initiatorIsCandidate
    );

    List<User> findAvailablePartners(Long userId, Language language, LocalDateTime dateTime);

    /** Слоты, когда есть свободные интервьюеры (по таблице schedules). */
    List<AvailableSlotDto> getAvailableSlotsAsCandidate(Long candidateUserId, Language language, int daysAhead);

    /** Слоты, когда есть свободные кандидаты (по таблице candidate_slots). */
    List<AvailableSlotDto> getAvailableSlotsAsInterviewer(Long interviewerUserId, Language language, int daysAhead);

    /** Пытается автоматически создать собеседование для интервьюера с первым подходящим кандидатом. */
    List<Interview> tryAutoMatchForInterviewer(Long interviewerUserId, Language language);

    /** Пытается автоматически создать собеседование для кандидата с первым подходящим интервьюером. */
    List<Interview> tryAutoMatchForCandidate(Long candidateUserId, Language language);

    /**
     * Присоединяет пользователя к существующему solo-слоту:
     * если asCandidate=true — устанавливает candidateId, иначе — interviewerId.
     */
    Interview joinInterview(Long interviewId, Long userId, boolean asCandidate);

    List<Interview> getUserInterviews(Long userId, InterviewStatus status);

    Interview cancelInterview(Long interviewId);

    Interview completeInterview(Long interviewId);
}

