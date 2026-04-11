package com.interviewpartner.bot.service;

import com.interviewpartner.bot.exception.UserNotFoundException;
import com.interviewpartner.bot.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
@Import(UserServiceImpl.class)
class UserServiceTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Test
    void registerUser_shouldCreateNewUserIfNotExists() {
        var created = userService.registerUser(1000L, "bob");

        assertThat(created.getId()).isNotNull();
        assertThat(created.getTelegramId()).isEqualTo(1000L);
        assertThat(created.getUsername()).isEqualTo("bob");
        assertThat(userRepository.existsByTelegramId(1000L)).isTrue();
    }

    @Test
    void registerUser_shouldReturnExistingUserIfExists() {
        var u1 = userService.registerUser(2000L, "first");
        var u2 = userService.registerUser(2000L, "second");

        assertThat(u2.getId()).isEqualTo(u1.getId());
        assertThat(userRepository.findAll()).hasSize(1);
    }

    @Test
    void getUserByTelegramId_shouldThrowIfNotFound() {
        assertThatThrownBy(() -> userService.getUserByTelegramId(9999L))
                .isInstanceOf(UserNotFoundException.class);
    }

}

