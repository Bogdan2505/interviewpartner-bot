package com.interviewpartner.bot.repository;

import com.interviewpartner.bot.model.Language;
import com.interviewpartner.bot.model.Level;
import com.interviewpartner.bot.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    void shouldSaveUserWithRequiredFields() {
        var user = User.builder()
                .telegramId(123L)
                .username("bogdan")
                .language(Language.RUSSIAN)
                .level(Level.JUNIOR)
                .build();

        var saved = userRepository.saveAndFlush(user);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getTelegramId()).isEqualTo(123L);
    }

    @Test
    void shouldEnforceUniqueTelegramId() {
        userRepository.saveAndFlush(User.builder()
                .telegramId(555L)
                .username("u1")
                .language(Language.RUSSIAN)
                .level(Level.JUNIOR)
                .build());

        assertThatThrownBy(() -> userRepository.saveAndFlush(User.builder()
                .telegramId(555L)
                .username("u2")
                .language(Language.ENGLISH)
                .level(Level.MIDDLE)
                .build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldFindByTelegramId() {
        userRepository.saveAndFlush(User.builder()
                .telegramId(777L)
                .username("u")
                .language(Language.ENGLISH)
                .level(Level.SENIOR)
                .build());

        var found = userRepository.findByTelegramId(777L);
        assertThat(found).isPresent();
        assertThat(found.get().getTelegramId()).isEqualTo(777L);
    }
}

