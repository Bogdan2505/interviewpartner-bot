package com.interviewpartner.bot.repository;

import com.interviewpartner.bot.model.User;
import com.interviewpartner.bot.model.Language;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByTelegramId(Long telegramId);

    boolean existsByTelegramId(Long telegramId);

    List<User> findByLanguageAndIdNot(Language language, Long id);
}

