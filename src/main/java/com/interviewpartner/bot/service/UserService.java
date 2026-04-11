package com.interviewpartner.bot.service;

import com.interviewpartner.bot.model.User;

public interface UserService {
    User registerUser(Long telegramId, String username);

    User getUserByTelegramId(Long telegramId);

    User getUserById(Long userId);
}

