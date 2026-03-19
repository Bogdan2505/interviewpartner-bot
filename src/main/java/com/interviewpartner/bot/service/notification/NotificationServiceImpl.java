package com.interviewpartner.bot.service.notification;

import com.interviewpartner.bot.model.Interview;
import com.interviewpartner.bot.model.InterviewStatus;
import com.interviewpartner.bot.repository.InterviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationServiceImpl implements NotificationService {

    private final InterviewRepository interviewRepository;

    @Override
    public List<Interview> findUpcomingInterviewsToCheck(LocalDateTime now) {
        // MVP для 4.1: проверяем ближайшие 60 минут.
        // 4.2 добавит конкретные окна 24ч/1ч/15м и отметку "уже отправлено".
        return interviewRepository.findByStatusAndDateTimeBetween(
                InterviewStatus.SCHEDULED,
                now,
                now.plusHours(1)
        );
    }
}

