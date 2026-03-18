package com.interviewpartner.bot.service;

import com.interviewpartner.bot.model.Interview;
import com.interviewpartner.bot.model.InterviewFormat;
import com.interviewpartner.bot.model.InterviewStatus;
import com.interviewpartner.bot.model.Language;
import com.interviewpartner.bot.model.User;

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

    List<Interview> getUserInterviews(Long userId, InterviewStatus status);

    Interview cancelInterview(Long interviewId);

    Interview completeInterview(Long interviewId);
}

