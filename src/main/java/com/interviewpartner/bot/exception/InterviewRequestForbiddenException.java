package com.interviewpartner.bot.exception;

/** Попытка принять/отклонить заявку не тем Telegram-пользователем, который указан как интервьюер. */
public class InterviewRequestForbiddenException extends RuntimeException {
    public InterviewRequestForbiddenException() {
        super("Not the interviewer for this request");
    }
}
