package com.interviewpartner.bot.repository;

import com.interviewpartner.bot.model.User;
import com.interviewpartner.bot.model.Language;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByTelegramId(Long telegramId);

    boolean existsByTelegramId(Long telegramId);

    List<User> findByLanguageAndIdNot(Language language, Long id);

    @Query("select u.telegramId from User u")
    List<Long> findAllTelegramIds();
}

