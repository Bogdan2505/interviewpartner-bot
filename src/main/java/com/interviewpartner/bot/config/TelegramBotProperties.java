package com.interviewpartner.bot.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "telegram.bot")
public class TelegramBotProperties {

    private String token = "";
    private String username = "";
}
