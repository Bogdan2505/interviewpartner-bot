package com.interviewpartner.bot.service.notification;

import com.interviewpartner.bot.model.Interview;
import com.interviewpartner.bot.model.ReminderType;

public interface ReminderSender {
    void sendReminder(Interview interview, ReminderType type);
}

