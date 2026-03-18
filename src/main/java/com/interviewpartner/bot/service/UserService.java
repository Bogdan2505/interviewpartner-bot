package com.interviewpartner.bot.service;

import com.interviewpartner.bot.model.Language;
import com.interviewpartner.bot.model.Level;
import com.interviewpartner.bot.model.User;

public interface UserService {
    User registerUser(Long telegramId, String username);

    User getUserByTelegramId(Long telegramId);

    User updateUserLanguage(Long userId, Language language);

    User updateUserLevel(Long userId, Level level);
}

