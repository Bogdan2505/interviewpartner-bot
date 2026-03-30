package com.interviewpartner.bot.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BotRootController {

    @GetMapping("/")
    public ResponseEntity<String> root() {
        return ResponseEntity.ok("InterviewPartner Bot OK");
    }
}
