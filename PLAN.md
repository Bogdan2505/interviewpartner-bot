# План разработки InterviewPartner Bot

## Этап 0: Настройка проекта

### Задача 0.1: Инициализация Spring Boot проекта
- [x] Создать базовый Spring Boot проект (Java 17, Spring Boot 3.x)
- [x] Подключить зависимости: Spring Web, Spring Data JPA, H2, PostgreSQL, Lombok
- [x] Настроить структуру пакетов: model, repository, service, controller, telegram, config, exception, util
- [x] Настроить профили dev (H2) / prod (PostgreSQL) и базовые свойства приложения
- [x] Добавить каркас TelegramBot
- [x] Добавить тест загрузки контекста Spring

### Задача 0.2: Подключение Telegram Bot API
- [ ] Добавить зависимость telegrambots-spring-boot-starter
- [ ] Создать класс TelegramBot, наследуемый от TelegramLongPollingBot
- [ ] Настроить токен бота через application.properties
- [ ] Написать тест для проверки подключения к API

### Задача 0.3: Настройка базы данных
- [ ] Настроить H2 для разработки
- [ ] Настроить PostgreSQL для продакшна
- [ ] Подключить Liquibase/Flyway для миграций
- [ ] Написать тест подключения к БД

## Этап 1: Модели данных

### Задача 1.1: Модель User
- [x] Сущность User + enum'ы Language/Level
- [x] Тесты репозитория (создание, уникальность telegramId, поиск по telegramId)

### Задача 1.2: Модель Interview
- [x] Сущность Interview + enum'ы InterviewFormat/InterviewStatus
- [x] Тесты репозитория (поиск по диапазону дат, проверка конфликтов)

### Задача 1.3: Модель Schedule
- [x] Сущность Schedule
- [x] Тесты репозитория (поиск по userId и dayOfWeek)

### Задача 1.4: Репозитории
- [x] UserRepository (findByTelegramId, existsByTelegramId)
- [x] InterviewRepository (findByDateTimeBetween, findConflictingInterviews)
- [x] ScheduleRepository (findByUserId, findByUserIdAndDayOfWeek)

## Этап 2: Сервисный слой

### Задача 2.1: UserService
- [x] registerUser / getUserByTelegramId / updateUserLanguage / updateUserLevel
- [x] Тесты: регистрация, получение, обновление, not found

### Задача 2.2: InterviewService
- [x] createInterview (валидация пользователей, проверка конфликтов)
- [x] cancelInterview / completeInterview
- [x] Тесты: создание, конфликт времени, обновление статусов

### Задача 2.3: ScheduleService
- [x] addAvailability / removeAvailability / getUserSchedule / isUserAvailable
- [x] Тесты: добавление, пересечения, доступность, удаление

