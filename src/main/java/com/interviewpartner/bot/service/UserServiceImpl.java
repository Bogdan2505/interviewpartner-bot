package com.interviewpartner.bot.service;

import com.interviewpartner.bot.exception.UserNotFoundException;
import com.interviewpartner.bot.model.Language;
import com.interviewpartner.bot.model.Level;
import com.interviewpartner.bot.model.User;
import com.interviewpartner.bot.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;

    @Override
    public User registerUser(Long telegramId, String username) {
        return userRepository.findByTelegramId(telegramId)
                .orElseGet(() -> {
                    User saved = userRepository.save(User.builder()
                            .telegramId(telegramId)
                            .username(username)
                            .build());
                    log.info("Зарегистрирован новый пользователь: userId={}, telegramId={}", saved.getId(), telegramId);
                    return saved;
                });
    }

    @Override
    @Transactional(readOnly = true)
    public User getUserByTelegramId(Long telegramId) {
        return userRepository.findByTelegramId(telegramId)
                .orElseThrow(() -> new UserNotFoundException("User with telegramId=" + telegramId + " not found"));
    }

    @Override
    @Transactional(readOnly = true)
    public User getUserById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User with id=" + userId + " not found"));
    }

    @Override
    public User updateUserLanguage(Long userId, Language language) {
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User with id=" + userId + " not found"));
        user.setLanguage(language);
        User saved = userRepository.save(user);
        log.info("Обновлён язык пользователя: userId={}, language={}", userId, language);
        return saved;
    }

    @Override
    public User updateUserLevel(Long userId, Level level) {
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User with id=" + userId + " not found"));
        user.setLevel(level);
        User saved = userRepository.save(user);
        log.info("Обновлён грейд пользователя: userId={}, level={}", userId, level);
        return saved;
    }
}

