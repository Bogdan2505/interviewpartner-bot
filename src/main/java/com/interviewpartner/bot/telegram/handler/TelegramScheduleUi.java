package com.interviewpartner.bot.telegram.handler;

import com.interviewpartner.bot.model.InterviewFormat;
import com.interviewpartner.bot.model.InterviewRequestStatus;
import com.interviewpartner.bot.model.Language;
import com.interviewpartner.bot.model.Level;

import java.util.EnumSet;

/**
 * Единые подписи и эмодзи для расписания и записи на собеседование в Telegram.
 */
public final class TelegramScheduleUi {

    /** Заявка на собеседование (ожидает решения). */
    public static final String REQUEST_ICON = "🫱";
    /** Согласованное собеседование. */
    public static final String CONFIRMED_ICON = "🤝";
    /** В календаре по дням: есть какие-то события/слоты. */
    public static final String DAY_HAS_ACTIVITY = "✅";

    private TelegramScheduleUi() {
    }

    /** Подпись грейда как в выборе при записи; null — «любой» / не указан. */
    public static String levelLabel(Level level) {
        if (level == null) {
            return "—";
        }
        return switch (level) {
            case JUNIOR -> "Junior";
            case MIDDLE -> "Middle";
            case SENIOR -> "Senior";
        };
    }

    /** Человекочитаемое направление/язык собеседования (как подписи кнопок выбора). */
    public static String languageLabel(Language language) {
        if (language == null) {
            return "—";
        }
        return switch (language) {
            case JAVA -> "Java";
            case CSHARP -> "C#";
            case CPP -> "C++";
            case PYTHON -> "Python";
            case ALGORITHMS -> "Algorithms";
            case PRODUCT_MANAGER -> "Product Manager";
            case JAVASCRIPT -> "JavaScript";
            case KOTLIN -> "Kotlin";
            case SWIFT -> "Swift";
            case GO -> "Go";
            case QA -> "QA";
            case DATA_ANALYTICS -> "Data Analytics";
            case BUSINESS_ANALYSIS -> "Business Analysis";
            case SYSTEM_ANALYSIS -> "System Analysis";
            case INFORMATION_SECURITY -> "ИБ";
            case ENGLISH -> "English";
            case RUSSIAN -> "Русский";
        };
    }

    public static String legendRequestAndConfirmed() {
        return "\n\n" + REQUEST_ICON + " — заявка на собеседование   "
                + CONFIRMED_ICON + " — согласованное собеседование";
    }

    public static String legendOpenPartnerSlot() {
        return "\n" + DAY_HAS_ACTIVITY + " — есть открытый слот другого участника";
    }

    /**
     * Один стикер на час: при согласованном собеседовании только 🤝, без 🫱 рядом.
     */
    public static void appendDominantInterviewStatusIcon(StringBuilder sb, EnumSet<InterviewRequestStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return;
        }
        if (statuses.contains(InterviewRequestStatus.ACCEPTED)) {
            sb.append(" ").append(CONFIRMED_ICON);
            return;
        }
        if (statuses.contains(InterviewRequestStatus.PENDING)) {
            sb.append(" ").append(REQUEST_ICON);
        }
    }

    /**
     * Два абзаца как в календаре записи и в /schedule: статус + партнёр, затем язык/формат/длительность/старт.
     */
    public static String scheduleRequestDetailsLines(
            InterviewRequestStatus status,
            String partnerLabel,
            Language language,
            InterviewFormat format,
            int durationMinutes,
            String startTimeHm) {
        String statusLine = status == InterviewRequestStatus.ACCEPTED
                ? CONFIRMED_ICON + " согласовано"
                : REQUEST_ICON + " заявка";
        return statusLine + ", партнёр: " + partnerLabel + "\n"
                + language + ", " + format + ", " + durationMinutes + " мин, старт " + startTimeHm;
    }
}
