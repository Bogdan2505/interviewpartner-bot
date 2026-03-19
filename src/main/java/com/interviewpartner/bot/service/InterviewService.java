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
            int durationMinutes
    );

    List<User> findAvailablePartners(Long userId, Language language, LocalDateTime dateTime);

    /** Слоты, когда можно записаться как кандидат (есть свободные интервьюеры). */
    List<AvailableSlotDto> getAvailableSlotsAsCandidate(Long candidateUserId, Language language, int daysAhead);

    /** Слоты, когда можно провести собеседование как интервьюер (есть свободные кандидаты). */
    List<AvailableSlotDto> getAvailableSlotsAsInterviewer(Long interviewerUserId, Language language, int daysAhead);

    List<Interview> getUserInterviews(Long userId, InterviewStatus status);

    Interview cancelInterview(Long interviewId);

    Interview completeInterview(Long interviewId);
}

