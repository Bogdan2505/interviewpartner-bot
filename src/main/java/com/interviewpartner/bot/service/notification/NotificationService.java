package com.interviewpartner.bot.service.notification;

import com.interviewpartner.bot.model.Interview;

import java.time.LocalDateTime;
import java.util.List;

public interface NotificationService {
    List<Interview> findUpcomingInterviewsToCheck(LocalDateTime now);
}

